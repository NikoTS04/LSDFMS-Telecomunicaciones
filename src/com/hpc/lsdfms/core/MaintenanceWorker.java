package com.hpc.lsdfms.core;

import java.util.function.BooleanSupplier;

/**
 * Hilo de mantenimiento en segundo plano (daemon, creado explícitamente). Se despierta con
 * {@code notifyAll} cuando el dataset queda desactualizado, espera un período de debounce
 * desde la última mutación y ejecuta un ciclo; o ejecuta el ciclo de inmediato si alguien
 * llama a {@link #request()}.
 *
 * <p>El ciclo en sí (PACK y reindexación bajo acceso exclusivo) lo aporta el servicio como
 * {@link Runnable}; un fallo del ciclo no termina el hilo. Orden de bloqueos: este monitor
 * puede tomar el del servicio (mediante el predicado), nunca al revés.
 */
final class MaintenanceWorker {
    private static final long IDLE = Long.MIN_VALUE;
    private final Runnable cycle;
    private final BooleanSupplier automatic;
    private final long debounceMs;
    private final Thread thread;
    private boolean requested;
    private boolean active;
    private boolean stopping;
    private long lastMutation = System.nanoTime();

    /**
     * @param cycle ejecuta un ciclo completo; debe capturar sus propios errores
     * @param automatic {@code true} si el dataset está desactualizado y puede mantenerse solo
     * @param debounceMs espera tras la última mutación antes de un ciclo automático
     */
    MaintenanceWorker(Runnable cycle, BooleanSupplier automatic, long debounceMs) {
        this.cycle = cycle;
        this.automatic = automatic;
        this.debounceMs = debounceMs;
        this.thread = new Thread(this::loop, "lsdfms-maintenance");
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    /** Avisa de una mutación: reinicia el debounce y despierta al hilo. */
    synchronized void mutated() {
        lastMutation = System.nanoTime();
        notifyAll();
    }

    /** Solicita un ciclo inmediato (las solicitudes repetidas se funden en una). */
    synchronized void request() {
        requested = true;
        notifyAll();
    }

    synchronized boolean isActive() {
        return active;
    }

    /** Hay un ciclo en curso o solicitado y aún no iniciado. */
    synchronized boolean isBusy() {
        return active || requested;
    }

    /** Espera a que no haya ciclo en curso ni solicitado y nada automático pendiente. */
    synchronized boolean awaitIdle(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (active || requested || automatic.getAsBoolean()) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                return false;
            }
            wait(Math.min(remaining, 50));
        }
        return true;
    }

    /** Detiene el hilo tras terminar el ciclo en curso (si lo hay) y espera su fin. */
    void close() throws InterruptedException {
        synchronized (this) {
            stopping = true;
            notifyAll();
        }
        thread.join();
    }

    private void loop() {
        while (true) {
            synchronized (this) {
                while (!stopping && !requested) {
                    long remaining = remainingDebounceMs();
                    if (remaining == IDLE) {
                        waitFor(0);
                    } else if (remaining <= 0) {
                        break;
                    } else {
                        waitFor(remaining);
                    }
                }
                if (stopping) {
                    notifyAll();
                    return;
                }
                requested = false;
                active = true;
            }
            try {
                cycle.run();
            } catch (RuntimeException | Error e) {
                // El ciclo registra sus fallos; el hilo sobrevive a cualquier excepción.
            } finally {
                synchronized (this) {
                    active = false;
                    notifyAll();
                }
            }
        }
    }

    /** Espera (0 = sin límite); una interrupción detiene el hilo. */
    private void waitFor(long millis) {
        try {
            wait(millis);
        } catch (InterruptedException e) {
            stopping = true;
        }
    }

    /** Milisegundos hasta que venza el debounce; {@link #IDLE} si no hay trabajo automático. */
    private long remainingDebounceMs() {
        if (!automatic.getAsBoolean()) {
            return IDLE;
        }
        long elapsedMs = (System.nanoTime() - lastMutation) / 1_000_000L;
        return debounceMs - elapsedMs;
    }
}
