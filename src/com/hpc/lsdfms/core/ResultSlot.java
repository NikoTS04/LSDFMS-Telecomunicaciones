package com.hpc.lsdfms.core;

/**
 * Futuro de un solo uso implementado como monitor: un hilo completa o falla el resultado
 * y otro lo espera. Solo la primera finalización tiene efecto.
 */
public final class ResultSlot<T> {
    /** La operación terminó con un error; la causa original está en {@link #getCause()}. */
    public static final class FailedException extends Exception {
        private static final long serialVersionUID = 1L;

        FailedException(Throwable cause) {
            super(cause.getMessage(), cause);
        }
    }

    /** El resultado no estuvo disponible dentro del plazo indicado. */
    public static final class TimedOutException extends Exception {
        private static final long serialVersionUID = 1L;

        TimedOutException(long millis) {
            super("Sin resultado tras " + millis + " ms");
        }
    }

    private boolean done;
    private T value;
    private Throwable failure;

    /** @return {@code true} si esta llamada fijó el resultado */
    public synchronized boolean complete(T result) {
        if (done) {
            return false;
        }
        value = result;
        done = true;
        notifyAll();
        return true;
    }

    /** @return {@code true} si esta llamada fijó el fallo */
    public synchronized boolean fail(Throwable error) {
        if (error == null) {
            throw new NullPointerException("error");
        }
        if (done) {
            return false;
        }
        failure = error;
        done = true;
        notifyAll();
        return true;
    }

    public synchronized boolean isDone() {
        return done;
    }

    /** Espera sin límite el resultado. */
    public synchronized T await() throws InterruptedException, FailedException {
        while (!done) {
            wait();
        }
        return result();
    }

    /** Espera hasta {@code timeoutMs} milisegundos (mayor que cero). */
    public synchronized T await(long timeoutMs)
            throws InterruptedException, FailedException, TimedOutException {
        if (timeoutMs <= 0) {
            throw new IllegalArgumentException("El plazo debe ser positivo: " + timeoutMs);
        }
        long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
        while (!done) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimedOutException(timeoutMs);
            }
            wait(remaining / 1_000_000L, (int) (remaining % 1_000_000L));
        }
        return result();
    }

    private T result() throws FailedException {
        if (failure != null) {
            throw new FailedException(failure);
        }
        return value;
    }
}
