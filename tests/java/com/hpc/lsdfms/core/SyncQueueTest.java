package com.hpc.lsdfms.core;

import java.util.ArrayList;
import java.util.List;

/** Estrés y semántica de SyncQueue y ResultSlot, sin java.util.concurrent. */
public final class SyncQueueTest {
    private static final int PRODUCERS = 8;
    private static final int PER_PRODUCER = 10_000;

    private SyncQueueTest() { }

    public static void main(String[] args) throws Exception {
        stress(1, 16);
        stress(2, 3);
        backpressure();
        closeWakesTakers();
        closeSemantics();
        slot();
    }

    private static void stress(int consumers, int capacity) throws Exception {
        SyncQueue<long[]> queue = new SyncQueue<>(capacity);
        List<Thread> producers = new ArrayList<>();
        List<Thread> takers = new ArrayList<>();
        List<List<long[]>> taken = new ArrayList<>();
        for (int c = 0; c < consumers; c++) {
            List<long[]> mine = new ArrayList<>();
            taken.add(mine);
            Thread thread = new Thread(() -> {
                try {
                    long[] item;
                    while ((item = queue.take()) != null) {
                        mine.add(item);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer-" + c);
            takers.add(thread);
            thread.start();
        }
        for (int p = 0; p < PRODUCERS; p++) {
            final int id = p;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < PER_PRODUCER; i++) {
                        queue.put(new long[]{id, i});
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "producer-" + p);
            producers.add(thread);
            thread.start();
        }
        for (Thread thread : producers) {
            thread.join();
        }
        queue.close();
        for (Thread thread : takers) {
            thread.join();
        }
        int total = 0;
        boolean[][] seen = new boolean[PRODUCERS][PER_PRODUCER];
        for (List<long[]> mine : taken) {
            long[] last = new long[PRODUCERS];
            java.util.Arrays.fill(last, -1);
            for (long[] item : mine) {
                int p = (int) item[0];
                int i = (int) item[1];
                check(!seen[p][i], "Duplicado " + p + ":" + i);
                seen[p][i] = true;
                check(i > last[p], "FIFO por productor roto en consumidor");
                last[p] = i;
                total++;
            }
        }
        check(total == PRODUCERS * PER_PRODUCER, "Pérdida de elementos: " + total);
        check(queue.size() == 0, "Cola vacía al final");
    }

    private static void backpressure() throws Exception {
        SyncQueue<Integer> queue = new SyncQueue<>(2);
        queue.put(1);
        queue.put(2);
        boolean[] done = new boolean[1];
        Thread blocked = new Thread(() -> {
            try {
                queue.put(3);
                synchronized (done) {
                    done[0] = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        blocked.start();
        Thread.sleep(150);
        synchronized (done) {
            check(!done[0], "put debe bloquear con la cola llena");
        }
        check(queue.size() == 2, "Capacidad respetada");
        check(queue.take() == 1, "FIFO");
        blocked.join(5000);
        check(!blocked.isAlive() && done[0], "put se libera al extraer");
        check(queue.take() == 2 && queue.take() == 3, "Orden preservado");
        // Una interrupción en put bloqueado se propaga.
        queue.put(4);
        queue.put(5);
        boolean[] interrupted = new boolean[1];
        Thread waiting = new Thread(() -> {
            try {
                queue.put(6);
            } catch (InterruptedException e) {
                interrupted[0] = true;
            }
        });
        waiting.start();
        Thread.sleep(100);
        waiting.interrupt();
        waiting.join(5000);
        check(interrupted[0], "InterruptedException propagada");
    }

    private static void closeWakesTakers() throws Exception {
        SyncQueue<String> queue = new SyncQueue<>(4);
        Object[] results = new Object[3];
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            final int slot = i;
            results[i] = "pendiente";
            Thread thread = new Thread(() -> {
                try {
                    results[slot] = queue.take();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads.add(thread);
            thread.start();
        }
        Thread.sleep(150);
        queue.close();
        for (Thread thread : threads) {
            thread.join(5000);
            check(!thread.isAlive(), "close debe despertar a los consumidores");
        }
        for (Object result : results) {
            check(result == null, "take tras close devuelve null");
        }
    }

    private static void closeSemantics() throws Exception {
        SyncQueue<String> queue = new SyncQueue<>(4);
        queue.put("a");
        queue.put("b");
        queue.close();
        queue.close();
        check(queue.isClosed(), "isClosed");
        try {
            queue.put("c");
            throw new AssertionError("put tras close debe fallar");
        } catch (IllegalStateException expected) {
            check(expected.getMessage() != null, "Mensaje claro");
        }
        check("a".equals(queue.take()) && "b".equals(queue.take()), "Drena lo pendiente");
        check(queue.take() == null, "Luego devuelve null");
        // Un put bloqueado se libera con error al cerrar.
        SyncQueue<Integer> full = new SyncQueue<>(1);
        full.put(1);
        Throwable[] error = new Throwable[1];
        Thread putter = new Thread(() -> {
            try {
                full.put(2);
            } catch (InterruptedException | IllegalStateException e) {
                error[0] = e;
            }
        });
        putter.start();
        Thread.sleep(100);
        full.close();
        putter.join(5000);
        check(error[0] instanceof IllegalStateException, "put bloqueado falla al cerrar");
    }

    private static void slot() throws Exception {
        ResultSlot<String> ok = new ResultSlot<>();
        Thread completer = new Thread(() -> {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            ok.complete("listo");
        });
        completer.start();
        check("listo".equals(ok.await()), "await entrega el valor");
        check(!ok.complete("otro") && !ok.fail(new RuntimeException()), "Un solo resultado");
        check("listo".equals(ok.await(1)), "await con plazo tras completar");

        ResultSlot<String> failed = new ResultSlot<>();
        failed.fail(new IllegalStateException("boom"));
        try {
            failed.await();
            throw new AssertionError("Debe propagar el fallo");
        } catch (ResultSlot.FailedException e) {
            check(e.getCause() instanceof IllegalStateException, "Causa original");
        }

        ResultSlot<String> never = new ResultSlot<>();
        long start = System.nanoTime();
        try {
            never.await(120);
            throw new AssertionError("Debe expirar");
        } catch (ResultSlot.TimedOutException expected) {
            check((System.nanoTime() - start) / 1_000_000 >= 100, "Espera el plazo");
        }
        check(!never.isDone(), "Sigue pendiente");

        ResultSlot<String> interruptible = new ResultSlot<>();
        boolean[] interrupted = new boolean[1];
        Thread waiter = new Thread(() -> {
            try {
                interruptible.await();
            } catch (InterruptedException e) {
                interrupted[0] = true;
            } catch (ResultSlot.FailedException e) {
                throw new AssertionError(e);
            }
        });
        waiter.start();
        Thread.sleep(100);
        waiter.interrupt();
        waiter.join(5000);
        check(interrupted[0], "await propaga la interrupción");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
