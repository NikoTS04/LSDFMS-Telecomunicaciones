package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.model.ColumnIndex;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Construye los índices de teléfono, timestamp y tamaño con un {@link Thread} explícito por
 * columna (paralelismo de tareas: las columnas no tienen dependencia funcional entre sí).
 *
 * <p>Cada hilo ordena con el mismo camino que el índice de teléfono (en memoria o por runs
 * externas con merge acotado) y publica en un archivo temporal. Solo cuando todos terminan
 * bien se reemplazan los tres archivos finales; ante un fallo se cancelan los demás hilos,
 * se espera su fin y se descartan los temporales, de modo que el conjunto anterior queda intacto.
 */
public final class ColumnIndexBuilder {
    /** Punto de observación/inyección de fallos de pruebas; se invoca dentro de cada hilo. */
    @FunctionalInterface
    interface Hook {
        void started(ColumnIndex column) throws IOException;
    }

    private final ParallelEngine.Scanner scanner;
    private final SequentialEngine.Operations operations;
    private final Hook hook;

    public ColumnIndexBuilder() {
        this(new ParallelEngine.Scanner() { }, new SequentialEngine.Operations() { });
    }

    ColumnIndexBuilder(ParallelEngine.Scanner scanner, SequentialEngine.Operations operations) {
        this(scanner, operations, column -> { });
    }

    ColumnIndexBuilder(ParallelEngine.Scanner scanner, SequentialEngine.Operations operations,
            Hook hook) {
        this.scanner = scanner;
        this.operations = operations;
        this.hook = hook;
    }

    /** Adquiere el bloqueo del dataset (uso desde la CLI) y devuelve los registros activos indexados. */
    public long build(Path directory) throws IOException {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            return buildLocked(access);
        }
    }

    /** El llamador ya posee el acceso; rechaza datasets con mantenimiento pendiente. */
    public long buildLocked(DatasetAccess access) throws IOException {
        access.requireReady();
        return buildLocked(access.directory(), DatasetFiles.list(access.directory()));
    }

    /** Para PACK: el marcador de mantenimiento sigue activo y el bloqueo lo posee el llamador. */
    long buildLocked(Path root, List<DatasetFiles.Source> sources) throws IOException {
        long count = 0;
        for (var source : sources) {
            count = Math.addExact(count, scanner.count(source.path()));
        }
        ColumnIndex[] columns = ColumnIndex.values();
        Map<ColumnIndex, Path> staged = new EnumMap<>(ColumnIndex.class);
        Thread[] threads = new Thread[columns.length];
        AtomicReference<Throwable> failure = new AtomicReference<>();
        boolean interrupted = false;
        try {
            for (ColumnIndex column : columns) {
                staged.put(column, Files.createTempFile(root,
                        ".lsdfms-col-" + column.columnName() + "-", ".tmp"));
            }
            long total = count;
            for (int i = 0; i < columns.length; i++) {
                ColumnIndex column = columns[i];
                threads[i] = new Thread(() -> {
                    try {
                        hook.started(column);
                        buildColumn(root, sources, total, column, staged.get(column));
                    } catch (Throwable t) {
                        if (!failure.compareAndSet(null, t) && !(t instanceof InterruptedIOException)) {
                            failure.get().addSuppressed(t);
                        }
                        for (Thread other : threads) {
                            if (other != null && other != Thread.currentThread()) {
                                other.interrupt();
                            }
                        }
                    }
                }, "lsdfms-index-" + column.columnName());
            }
            for (Thread thread : threads) {
                thread.start();
            }
            interrupted = joinAll(threads);
            if (failure.get() == null && interrupted) {
                throw new InterruptedIOException("Indexación por columnas interrumpida");
            }
            rethrow(failure.get());
            publish(root, staged);
            return count;
        } catch (IOException | RuntimeException | Error e) {
            // Si falló el arranque de un hilo, se cancela y espera a los ya iniciados.
            for (Thread thread : threads) {
                if (thread != null && thread.getState() != Thread.State.NEW) {
                    thread.interrupt();
                }
            }
            interrupted |= joinAll(threads);
            throw e;
        } finally {
            for (Path temporary : staged.values()) {
                Files.deleteIfExists(temporary);
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** Espera a todos aunque el llamador sea interrumpido; devuelve si lo fue. */
    private static boolean joinAll(Thread[] threads) {
        boolean interrupted = false;
        for (Thread thread : threads) {
            while (thread != null && thread.getState() != Thread.State.NEW && thread.isAlive()) {
                try {
                    thread.join();
                } catch (InterruptedException e) {
                    interrupted = true;
                    for (Thread other : threads) {
                        if (other != null) {
                            other.interrupt();
                        }
                    }
                }
            }
        }
        return interrupted;
    }

    private static void rethrow(Throwable t) throws IOException {
        if (t == null) {
            return;
        }
        if (t instanceof IOException io) {
            throw io;
        }
        if (t instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (t instanceof Error error) {
            throw error;
        }
        throw new IOException("Falló un hilo de indexación", t);
    }

    private void buildColumn(Path root, List<DatasetFiles.Source> sources, long count,
            ColumnIndex column, Path staging) throws IOException {
        ParallelEngine.Scanner columnScanner = scannerFor(column);
        if (ExternalIndex.required(count, ColumnIndex.values().length)) {
            ExternalIndex.build(root, sources, count, 1, columnScanner, PackMaintenance::sequential,
                    (result, ignored) -> operations.replace(result, staging),
                    ColumnIndex.values().length);
            return;
        }
        IndexTuples tuples = IndexTuples.allocate(count, ColumnIndex.values().length);
        int[] position = {0};
        for (var source : sources) {
            columnScanner.scan(source.path(),
                    (key, offset) -> tuples.put(position[0]++, key, source.id(), offset));
        }
        if (position[0] != count) {
            throw new IOException("El dataset cambió durante INDEX de " + column.columnName());
        }
        tuples.sort(0, tuples.size);
        IndexWriter.write(staging, tuples, tuples.size);
        IndexTuples.checkInterrupted();
    }

    /** Teléfono usa el escáner inyectable de los motores; las demás leen la cabecera completa. */
    private ParallelEngine.Scanner scannerFor(ColumnIndex column) {
        return new ParallelEngine.Scanner() {
            @Override
            public long count(Path path) throws IOException {
                return scanner.count(path);
            }

            @Override
            public void scan(Path path, BinaryFileHandler.ActiveRecordConsumer consumer)
                    throws IOException {
                switch (column) {
                    case PHONE -> scanner.scan(path, consumer);
                    case TIMESTAMP -> BinaryFileHandler.scanHeaders(path,
                            (deleted, length, phone, timestamp, offset) -> {
                                if (!deleted) {
                                    consumer.accept(timestamp, offset);
                                }
                            });
                    case SIZE -> BinaryFileHandler.scanHeaders(path,
                            (deleted, length, phone, timestamp, offset) -> {
                                if (!deleted) {
                                    consumer.accept(length, offset);
                                }
                            });
                }
            }
        };
    }

    /** Reemplaza los tres archivos; si falla a mitad se invalida el conjunto completo. */
    private void publish(Path root, Map<ColumnIndex, Path> staged) throws IOException {
        try {
            IndexTuples.checkInterrupted();
            for (ColumnIndex column : ColumnIndex.values()) {
                Path target = root.resolve(column.fileName());
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Destino de índice no regular: " + target);
                }
            }
            for (ColumnIndex column : ColumnIndex.values()) {
                operations.replace(staged.get(column), root.resolve(column.fileName()));
            }
        } catch (IOException | RuntimeException | Error e) {
            List<Path> finals = new ArrayList<>();
            for (ColumnIndex column : ColumnIndex.values()) {
                finals.add(root.resolve(column.fileName()));
            }
            for (Path file : finals) {
                if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    try {
                        Files.delete(file);
                    } catch (IOException suppressed) {
                        e.addSuppressed(suppressed);
                    }
                }
            }
            throw e;
        }
    }
}
