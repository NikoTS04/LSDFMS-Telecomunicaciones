package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.search.BinaryIndexSearch;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Fallos coordinados, límites de recursos y recuperación de PACK paralelo. */
public final class ParallelPackFaultTest {
    private ParallelPackFaultTest() { }

    public static void main(String[] args) throws Exception {
        for (String mode : new String[]{"copy", "before", "after", "atomic", "index", "scan"}) {
            int files = "copy".equals(mode) ? 5 : 2;
            Path directory = fixture(Path.of(args[0]), mode, files);
            byte[] original = Files.readAllBytes(directory.resolve("F_1.dat"));
            byte[] packed = Arrays.copyOfRange(original, 24, original.length);
            CountDownLatch entered = new CountDownLatch(2);
            AtomicBoolean cancelled = new AtomicBoolean();
            SequentialEngine.Operations operations = new SequentialEngine.Operations() {
                @Override
                public void compact(Path source, Path temporary) throws IOException {
                    check(Files.isRegularFile(directory.resolve(DatasetAccess.MAINTENANCE_NAME)),
                            "Marcador antes de modificar datos");
                    check(!Files.exists(directory.resolve(SequentialEngine.INDEX_NAME)),
                            "Índice invalidado antes de copiar");
                    try {
                        new BinaryIndexSearch().search(directory, 7, (id, offset, record) -> {
                            throw new AssertionError("No entregar datos durante mantenimiento");
                        });
                        throw new AssertionError("Búsqueda debe estar excluida");
                    } catch (IOException expected) {
                        check(expected.getMessage().contains("ocupado"), "Exclusión de consultas");
                    }
                    if ("copy".equals(mode)) {
                        entered.countDown();
                        try {
                            check(entered.await(10, TimeUnit.SECONDS), "Dos copias concurrentes");
                            if (source.getFileName().toString().equals("F_1.dat")) {
                                Files.write(temporary, new byte[]{1});
                                throw new IOException("Copia fallida inyectada");
                            }
                            new CountDownLatch(1).await();
                            throw new AssertionError("Debe ser cancelada");
                        } catch (InterruptedException e) {
                            cancelled.set(true);
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("Cancelada");
                        }
                    }
                    SequentialEngine.Operations.super.compact(source, temporary);
                }

                @Override
                public void replace(Path temporary, Path target) throws IOException {
                    boolean data = target.getFileName().toString().endsWith(".dat");
                    boolean first = target.getFileName().toString().equals("F_1.dat");
                    if ("before".equals(mode) && first) {
                        throw new IOException("Antes del reemplazo");
                    }
                    if ("atomic".equals(mode) && first) {
                        throw new AtomicMoveNotSupportedException(
                                temporary.toString(), target.toString(), "simulado");
                    }
                    if ("index".equals(mode) && !data) {
                        throw new IOException("Publicación de índice fallida");
                    }
                    SequentialEngine.Operations.super.replace(temporary, target);
                    if ("after".equals(mode) && first) {
                        throw new IOException("Después del reemplazo");
                    }
                }
            };
            ParallelEngine.Scanner scanner = new ParallelEngine.Scanner() {
                @Override
                public long count(Path source) throws IOException {
                    if ("scan".equals(mode)) {
                        throw new IOException("Reindexación fallida");
                    }
                    return ParallelEngine.Scanner.super.count(source);
                }
            };
            try {
                new ParallelEngine(2, scanner, operations).pack(directory);
                throw new AssertionError("Debe fallar: " + mode);
            } catch (IOException expected) {
                check(Files.exists(directory.resolve(DatasetAccess.MAINTENANCE_NAME)), "Conserva marcador");
                check(!Files.exists(directory.resolve(SequentialEngine.INDEX_NAME)), "Sin índice parcial");
            }
            if ("copy".equals(mode)) {
                check(cancelled.get(), "Espera al trabajador cancelado");
            }
            noWorkersOrTemporaries(directory);
            for (int id = 1; id <= files; id++) {
                byte[] actual = Files.readAllBytes(directory.resolve("F_" + id + ".dat"));
                check(Arrays.equals(actual, original) || Arrays.equals(actual, packed),
                        "Solo original completo o compactado completo, nunca parcial");
            }
            // Incluso un índice antiguo restaurado manualmente queda bloqueado.
            Files.write(directory.resolve(SequentialEngine.INDEX_NAME),
                    ByteBuffer.allocate(20).putLong(7).putInt(1).putLong(24).array());
            try {
                new BinaryIndexSearch().search(directory, 7, (id, offset, record) -> { });
                throw new AssertionError("No consultar offsets antiguos");
            } catch (IOException expected) {
                check(expected.getMessage().contains("recover"), "Solicita recuperación");
            }
            if ("after".equals(mode)) {
                new SequentialEngine().recover(directory);
            } else {
                new ParallelEngine(2).recover(directory);
            }
            for (int id = 1; id <= files; id++) {
                check(Arrays.equals(Files.readAllBytes(directory.resolve("F_" + id + ".dat")), packed),
                        "Activos intactos tras recuperar");
            }
            check(new BinaryIndexSearch().search(directory, 7, (id, offset, record) -> {
                check(offset == 0, "Offset reconstruido");
            }) == files, "Todas las coincidencias");
            check(!Files.exists(directory.resolve(DatasetAccess.MAINTENANCE_NAME)), "Fin de recovery");
            noWorkersOrTemporaries(directory);
        }
        bounds(Path.of(args[0]));
        interruption(Path.of(args[0]));
    }

    private static void bounds(Path root) throws Exception {
        Path directory = fixture(root, "bounds", 5);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(2);
        new ParallelEngine(2, new SequentialEngine.Operations() {
            @Override
            public void compact(Path source, Path temporary) throws IOException {
                peak.accumulateAndGet(active.incrementAndGet(), Math::max);
                try {
                    entered.countDown();
                    try {
                        check(entered.await(10, TimeUnit.SECONDS), "Paralelismo real");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Cancelada");
                    }
                    try (var files = Files.list(directory)) {
                        check(files.filter(path -> path.toString().endsWith(".tmp")).count() <= 2,
                                "Temporales simultáneos limitados por trabajadores");
                    }
                    SequentialEngine.Operations.super.compact(source, temporary);
                } finally {
                    active.decrementAndGet();
                }
            }
        }).pack(directory);
        check(peak.get() == 2 && active.get() == 0, "Dos trabajadores efectivos, no cinco");
        noWorkersOrTemporaries(directory);
    }

    private static void interruption(Path root) throws Exception {
        Path directory = fixture(root, "interrupt", 2);
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean preserved = new AtomicBoolean();
        AtomicReference<Throwable> error = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                new ParallelEngine(2, new SequentialEngine.Operations() {
                    @Override
                    public void compact(Path source, Path temporary) throws IOException {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("Copia interrumpida");
                        }
                    }
                }).pack(directory);
                error.set(new AssertionError("Debe fallar al interrumpir"));
            } catch (IOException expected) {
                preserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable unexpected) {
                error.set(unexpected);
            }
        }, "pack-caller-probe");
        caller.start();
        check(started.await(10, TimeUnit.SECONDS), "Debe entrar en copia");
        caller.interrupt();
        caller.join(10000);
        check(!caller.isAlive() && error.get() == null && preserved.get(), "Interrupción preservada");
        noWorkersOrTemporaries(directory);
        check(Files.exists(directory.resolve(DatasetAccess.MAINTENANCE_NAME)), "Recuperación requerida");
        new ParallelEngine(2).recover(directory);
    }

    private static Path fixture(Path root, String name, int files) throws IOException {
        Path directory = Files.createDirectory(root.resolve(name));
        byte[] record = ByteBuffer.allocate(49).put((byte) 1).putInt(3).putLong(7).putLong(0)
                .put(new byte[]{1, 2, 3}).put((byte) 0).putInt(4).putLong(7).putLong(5)
                .put(new byte[]{0, -1, 5, 6}).array();
        for (int id = 1; id <= files; id++) {
            Files.write(directory.resolve("F_" + id + ".dat"), record);
        }
        new SequentialEngine().index(directory);
        return directory;
    }

    private static void noWorkersOrTemporaries(Path directory) throws IOException {
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(thread -> thread.isAlive()
                && (thread.getName().startsWith("lsdfms-pack-")
                        || thread.getName().startsWith("lsdfms-index-"))), "Sin trabajadores vivos");
        try (var paths = Files.list(directory)) {
            check(paths.noneMatch(path -> path.toString().endsWith(".tmp")), "Sin temporales propios");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
