package com.hpc.lsdfms.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Fallos coordinados sin sleeps ni carreras temporales en las aserciones. */
public final class ParallelFailureTest {
    private ParallelFailureTest() { }

    public static void main(String[] args) throws Exception {
        for (boolean failInScan : new boolean[]{false, true}) {
            Path directory = fixture(Path.of(args[0]), failInScan ? "scan" : "count");
            byte[] before = Files.readAllBytes(directory.resolve(SequentialEngine.INDEX_NAME));
            CountDownLatch entered = new CountDownLatch(2);
            AtomicBoolean cancelled = new AtomicBoolean();
            ParallelEngine.Scanner scanner = new ParallelEngine.Scanner() {
                private void failOrWait(Path path) throws IOException {
                    entered.countDown();
                    try {
                        if (!entered.await(10, TimeUnit.SECONDS)) {
                            throw new AssertionError("No iniciaron ambos trabajadores");
                        }
                        if (path.getFileName().toString().equals("F_1.dat")) {
                            throw new IOException("Fallo inyectado");
                        }
                        new CountDownLatch(1).await();
                        throw new AssertionError("No debe terminar sin cancelación");
                    } catch (InterruptedException e) {
                        cancelled.set(true);
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException("Tarea cancelada");
                    }
                }

                @Override
                public long count(Path path) throws IOException {
                    if (!failInScan) {
                        failOrWait(path);
                    }
                    return ParallelEngine.Scanner.super.count(path);
                }

                @Override
                public void scan(Path path, com.hpc.lsdfms.io.BinaryFileHandler.ActiveRecordConsumer out)
                        throws IOException {
                    failOrWait(path);
                }
            };
            try {
                new ParallelEngine(2, scanner).index(directory);
                throw new AssertionError("Debe propagar el fallo");
            } catch (IOException expected) {
                check(expected.getMessage().contains("inyectado"), "Conserva causa original");
            }
            check(cancelled.get(), "Espera cancelación del otro trabajador");
            check(Arrays.equals(before, Files.readAllBytes(
                    directory.resolve(SequentialEngine.INDEX_NAME))), "Conserva índice anterior");
            assertNoWorkers();
            new SequentialEngine().index(directory); // El lock fue liberado tras terminar las tareas.
        }
        Path directory = fixture(Path.of(args[0]), "interruption");
        byte[] before = Files.readAllBytes(directory.resolve(SequentialEngine.INDEX_NAME));
        CountDownLatch started = new CountDownLatch(1);
        AtomicBoolean preservedInterrupt = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                new ParallelEngine(2, new ParallelEngine.Scanner() {
                    @Override
                    public long count(Path path) throws IOException {
                        started.countDown();
                        try {
                            new CountDownLatch(1).await();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException("Cancelado");
                        }
                        return 0;
                    }
                }).index(directory);
                failure.set(new AssertionError("Debe fallar tras interrupción"));
            } catch (IOException expected) {
                preservedInterrupt.set(Thread.currentThread().isInterrupted());
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "parallel-caller-probe");
        caller.start();
        check(started.await(10, TimeUnit.SECONDS), "La operación debe empezar");
        caller.interrupt();
        caller.join(10000);
        check(!caller.isAlive(), "El llamador debe terminar");
        check(failure.get() == null, "Sin errores inesperados: " + failure.get());
        check(preservedInterrupt.get(), "Conserva el estado interrumpido del llamador");
        check(Arrays.equals(before, Files.readAllBytes(
                directory.resolve(SequentialEngine.INDEX_NAME))), "No publica un índice parcial");
        assertNoWorkers();
        new SequentialEngine().index(directory);
        IndexTuples tuples = new IndexTuples(10000);
        Thread.currentThread().interrupt();
        try {
            tuples.sort(0, tuples.size);
            throw new AssertionError("El sort debe responder a cancelación");
        } catch (InterruptedIOException expected) {
            check(Thread.currentThread().isInterrupted(), "El sort no borra la interrupción");
        } finally {
            Thread.interrupted();
        }
    }

    private static Path fixture(Path root, String name) throws IOException {
        Path directory = Files.createDirectory(root.resolve(name));
        byte[] record = ByteBuffer.allocate(21).put((byte) 0).putInt(0).putLong(7).putLong(0).array();
        Files.write(directory.resolve("F_1.dat"), record);
        Files.write(directory.resolve("F_3.dat"), record);
        new SequentialEngine().index(directory);
        return directory;
    }

    private static void assertNoWorkers() {
        check(Thread.getAllStackTraces().keySet().stream().noneMatch(
                thread -> thread.isAlive() && thread.getName().startsWith("lsdfms-index-")),
                "No quedan trabajadores vivos");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
