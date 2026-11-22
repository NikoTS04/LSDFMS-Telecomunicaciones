package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.DatasetFiles;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** INDEX por archivos y ordenación paralela, sin commonPool ni objetos por registro. */
public final class ParallelEngine {
    public static final int MAX_WORKERS = 256;
    private final int workers;
    private final Scanner scanner;

    interface Scanner {
        default long count(Path path) throws IOException {
            return BinaryFileHandler.inspect(path).active();
        }

        default void scan(Path path, BinaryFileHandler.ActiveRecordConsumer consumer)
                throws IOException {
            BinaryFileHandler.scan(path, consumer);
        }
    }

    public ParallelEngine(int workers) {
        this(workers, new Scanner() { });
    }

    ParallelEngine(int workers, Scanner scanner) {
        if (workers < 1 || workers > MAX_WORKERS) {
            throw new IllegalArgumentException("Trabajadores deben estar entre 1 y " + MAX_WORKERS);
        }
        this.workers = workers;
        this.scanner = scanner;
    }

    public long index(Path directory) throws IOException {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            access.requireReady();
            Path root = access.directory();
            var sources = DatasetFiles.list(root);
            int parallelism = Math.min(workers, sources.size());
            AtomicInteger threadId = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(parallelism, task ->
                    new Thread(task, "lsdfms-index-" + threadId.incrementAndGet()));
            try {
                long[] counts = new long[sources.size()];
                List<Callable<Void>> tasks = new ArrayList<>();
                for (int i = 0; i < sources.size(); i++) {
                    int file = i;
                    tasks.add(() -> {
                        counts[file] = scanner.count(sources.get(file).path());
                        return null;
                    });
                }
                run(executor, tasks);
                long count = 0;
                for (long amount : counts) {
                    count = Math.addExact(count, amount);
                }
                IndexTuples tuples = IndexTuples.allocate(count, 2);
                tasks.clear();
                int start = 0;
                for (int i = 0; i < sources.size(); i++) {
                    var source = sources.get(i);
                    int from = start;
                    int to = from + (int) counts[i];
                    start = to;
                    tasks.add(() -> {
                        int[] position = {from};
                        scanner.scan(source.path(), (phone, offset) -> {
                            if (position[0] >= to) {
                                throw new IOException("El dataset creció durante INDEX");
                            }
                            tuples.put(position[0]++, phone, source.id(), offset);
                        });
                        if (position[0] != to) {
                            throw new IOException("El dataset cambió durante INDEX");
                        }
                        return null;
                    });
                }
                run(executor, tasks);
                IndexTuples sorted = sort(executor, tuples, parallelism);
                IndexWriter.publish(root, sorted, (temporary, target) ->
                        Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING));
                return count;
            } finally {
                stopAndJoin(executor);
            }
        }
    }

    private static IndexTuples sort(ExecutorService executor, IndexTuples tuples, int workers)
            throws IOException {
        if (tuples.size == 0) {
            return tuples;
        }
        int runs = Math.min(workers, tuples.size);
        int[] boundaries = new int[runs + 1];
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i <= runs; i++) {
            boundaries[i] = (int) ((long) tuples.size * i / runs);
        }
        for (int i = 0; i < runs; i++) {
            int from = boundaries[i];
            int to = boundaries[i + 1];
            tasks.add(() -> {
                tuples.sort(from, to);
                return null;
            });
        }
        run(executor, tasks);
        if (runs == 1) {
            return tuples;
        }
        IndexTuples current = tuples;
        IndexTuples other = new IndexTuples(tuples.size);
        for (int width = 1; width < runs; width *= 2) {
            tasks.clear();
            IndexTuples source = current;
            IndexTuples target = other;
            for (int i = 0; i < runs; i += 2 * width) {
                int from = boundaries[i];
                int middle = boundaries[Math.min(i + width, runs)];
                int to = boundaries[Math.min(i + 2 * width, runs)];
                tasks.add(() -> {
                    source.merge(from, middle, to, target);
                    return null;
                });
            }
            run(executor, tasks);
            current = target;
            other = source;
        }
        return current;
    }

    /** Observa fallos en orden de finalización, no de envío. */
    private static void run(ExecutorService executor, List<Callable<Void>> tasks)
            throws IOException {
        var completion = new ExecutorCompletionService<Void>(executor);
        List<Future<Void>> futures = new ArrayList<>();
        try {
            for (var task : tasks) {
                futures.add(completion.submit(task));
            }
            for (int i = 0; i < tasks.size(); i++) {
                completion.take().get();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("INDEX paralelo interrumpido");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            if (e.getCause() instanceof Error error) {
                throw error;
            }
            throw new IOException("Falló un trabajador de INDEX", e.getCause());
        } finally {
            for (var future : futures) {
                future.cancel(true);
            }
        }
    }

    /** No libera el dataset mientras sobreviva una tarea de esta operación. */
    private static void stopAndJoin(ExecutorService executor) {
        boolean interrupted = Thread.interrupted();
        executor.shutdownNow();
        try {
            while (true) {
                try {
                    if (executor.awaitTermination(1, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
