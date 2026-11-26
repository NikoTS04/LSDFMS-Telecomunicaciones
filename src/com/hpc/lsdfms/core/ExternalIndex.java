package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.IndexEntry;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.Semaphore;

/** Runs con presupuesto global y merge de fan-in acotado; sin objetos por entrada. */
final class ExternalIndex {
    private static final int BUFFER_BYTES = 20 * 2048;
    private ExternalIndex() { }

    private static long budget() {
        long configured = number("memoryMiB", 128, 4, 4096) * 1024L * 1024;
        return Math.min(configured, Runtime.getRuntime().maxMemory() / 4);
    }

    private static int number(String name, int fallback, int min, int max) {
        int value = Integer.parseInt(System.getProperty("lsdfms.index." + name,
                Integer.toString(fallback)));
        if (value < min || value > max) {
            throw new IllegalArgumentException("lsdfms.index." + name + " fuera de límites");
        }
        return value;
    }

    static boolean required(long count, int copies) throws IOException {
        String mode = System.getProperty("lsdfms.index.mode", "auto");
        if (!List.of("auto", "memory", "external").contains(mode)) {
            throw new IllegalArgumentException("lsdfms.index.mode debe ser auto, memory o external");
        }
        long bytes = budget() / 2; // Reserva global para buffers y metadatos.
        if (mode.equals("memory") && count > bytes / (IndexEntry.BYTES * copies)) {
            throw new IOException("Índice excede el presupuesto en memoria");
        }
        return mode.equals("external") || (mode.equals("auto")
                && count > bytes / (IndexEntry.BYTES * copies));
    }

    /** runner debe esperar la terminación de todas sus tareas incluso si falla. */
    static long build(Path directory, List<DatasetFiles.Source> sources, long count,
            int workers, ParallelEngine.Scanner scanner, PackMaintenance.Runner runner,
            IndexWriter.Replacement replacement) throws IOException {
        long bytes = budget();
        if (bytes < 1024 * 1024) {
            throw new IOException("Heap insuficiente para ordenación externa");
        }
        int slots = Math.max(1, Math.min(workers, (int) (bytes / (512 * 1024))));
        int capacity = (int) Math.min(Integer.MAX_VALUE - 8L, bytes / 2 / slots / IndexEntry.BYTES);
        capacity = Math.min(capacity, number("chunkEntries", Integer.MAX_VALUE, 1, Integer.MAX_VALUE));
        int fanIn = Math.min(number("fanIn", 16, 2, 64),
                Math.max(2, (int) (bytes / 4 / slots / BUFFER_BYTES)));
        Path workspace = Files.createTempDirectory(directory, ".lsdfms-index-");
        try {
            List<List<Path>> byFile = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                byFile.add(new ArrayList<>());
            }
            Semaphore permits = new Semaphore(slots);
            int chunkSize = capacity;
            long[] totals = new long[sources.size()];
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                int file = i;
                tasks.add(() -> {
                    try {
                        permits.acquire();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Generación de runs interrumpida");
                    }
                    try {
                        IndexTuples tuples = new IndexTuples(chunkSize);
                        List<List<Path>> levels = new ArrayList<>();
                        int[] filled = {0};
                        var source = sources.get(file);
                        scanner.scan(source.path(), (phone, offset) -> {
                            tuples.put(filled[0]++, phone, source.id(), offset);
                            totals[file]++;
                            if (filled[0] == chunkSize) {
                                addRun(workspace, levels, spill(workspace, tuples, filled[0]), fanIn);
                                filled[0] = 0;
                            }
                        });
                        if (filled[0] != 0) {
                            addRun(workspace, levels, spill(workspace, tuples, filled[0]), fanIn);
                        }
                        for (var level : levels) {
                            byFile.get(file).addAll(level);
                        }
                    } finally {
                        permits.release();
                    }
                    return null;
                });
            }
            runner.run(tasks);
            long actual = 0;
            List<Path> runs = new ArrayList<>();
            for (int i = 0; i < totals.length; i++) {
                actual = Math.addExact(actual, totals[i]);
                runs.addAll(byFile.get(i));
            }
            if (actual != count) {
                throw new IOException("El dataset cambió durante INDEX externo");
            }
            if (runs.isEmpty()) {
                Path empty = Files.createTempFile(workspace, "empty-", ".run");
                IndexWriter.write(empty, new IndexTuples(0), 0);
                runs.add(empty);
            }
            while (runs.size() > 1) {
                List<Path> next = new ArrayList<>();
                for (int start = 0; start < runs.size(); start += fanIn) {
                    var group = runs.subList(start, Math.min(runs.size(), start + fanIn));
                    Path merged = Files.createTempFile(workspace, "merge-", ".run");
                    merge(group, merged);
                    next.add(merged);
                    for (Path input : group) {
                        Files.delete(input);
                    }
                }
                runs = next;
            }
            Path result = runs.get(0);
            if (Files.size(result) != Math.multiplyExact(count, IndexEntry.BYTES)) {
                throw new IOException("Conteo de merge externo incorrecto");
            }
            IndexTuples.checkInterrupted();
            replacement.replace(result, directory.resolve(SequentialEngine.INDEX_NAME));
            return count;
        } finally {
            // Solo este directorio creado por esta operación; nunca huérfanos anteriores.
            try (var paths = Files.list(workspace)) {
                for (Path path : paths.toList()) {
                    Files.deleteIfExists(path);
                }
            }
            Files.delete(workspace);
        }
    }

    private static Path spill(Path workspace, IndexTuples tuples, int count)
            throws IOException {
        tuples.sort(0, count);
        Path run = Files.createTempFile(workspace, "sorted-", ".run");
        IndexWriter.write(run, tuples, count);
        return run;
    }

    /** Compactación por niveles: no retiene una ruta en RAM por cada bloque emitido. */
    private static void addRun(Path workspace, List<List<Path>> levels, Path run, int fanIn)
            throws IOException {
        for (int depth = 0; ; depth++) {
            if (depth == levels.size()) {
                levels.add(new ArrayList<>());
            }
            var level = levels.get(depth);
            level.add(run);
            if (level.size() < fanIn) {
                return;
            }
            Path combined = Files.createTempFile(workspace, "level-", ".run");
            merge(level, combined);
            for (Path input : level) {
                Files.delete(input);
            }
            level.clear();
            run = combined;
        }
    }

    static void merge(List<Path> inputs, Path output) throws IOException {
        List<Cursor> opened = new ArrayList<>();
        var queue = new PriorityQueue<Cursor>(Comparator.comparingLong((Cursor c) -> c.phone)
                .thenComparingInt(c -> c.fileId).thenComparingLong(c -> c.offset));
        try (FileChannel target = FileChannel.open(output, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            for (Path path : inputs) {
                Cursor cursor = new Cursor(path);
                opened.add(cursor);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES).order(CdrFormat.BYTE_ORDER);
            long written = 0;
            while (!queue.isEmpty()) {
                if ((written++ & 4095) == 0) {
                    IndexTuples.checkInterrupted();
                }
                Cursor cursor = queue.remove();
                if (buffer.remaining() < IndexEntry.BYTES) {
                    IndexWriter.flush(target, buffer);
                }
                IndexEntry.write(buffer, cursor.phone, cursor.fileId, cursor.offset);
                if (cursor.advance()) {
                    queue.add(cursor);
                }
            }
            IndexWriter.flush(target, buffer);
            target.force(true);
        } finally {
            IOException failure = null;
            for (Cursor cursor : opened) {
                try {
                    cursor.channel.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class Cursor {
        final FileChannel channel;
        final ByteBuffer buffer = ByteBuffer.allocate(BUFFER_BYTES).order(CdrFormat.BYTE_ORDER);
        final long size;
        long position;
        long phone;
        int fileId;
        long offset;
        boolean first = true;

        Cursor(Path path) throws IOException {
            channel = FileChannel.open(path, StandardOpenOption.READ);
            try {
                size = channel.size();
                if (size % IndexEntry.BYTES != 0) {
                    throw new IOException("Run truncada: " + path);
                }
                buffer.limit(0);
            } catch (IOException | RuntimeException e) {
                channel.close();
                throw e;
            }
        }

        boolean advance() throws IOException {
            if (!buffer.hasRemaining()) {
                if (position == size) {
                    return false;
                }
                buffer.clear();
                buffer.limit((int) Math.min(buffer.capacity(), size - position));
                BinaryFileHandler.readFully(channel, buffer, position);
                position += buffer.position();
                buffer.flip();
            }
            long key = buffer.getLong();
            int id = buffer.getInt();
            long start = buffer.getLong();
            if (id <= 0 || start < 0 || (!first && (key < phone
                    || (key == phone && (id < fileId || (id == fileId && start < offset)))))) {
                throw new IOException("Run inválida o desordenada");
            }
            phone = key;
            fileId = id;
            offset = start;
            first = false;
            return true;
        }
    }
}
