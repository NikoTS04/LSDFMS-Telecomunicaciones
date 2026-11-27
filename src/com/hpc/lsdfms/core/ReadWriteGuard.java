package com.hpc.lsdfms.core;

/**
 * Guardia lectores/escritor implementada como monitor ({@code synchronized}, {@code wait},
 * {@code notifyAll}) con orden de llegada estricto.
 *
 * <p>Política: cada solicitante toma un turno y solo avanza cuando es el siguiente en
 * atenderse. Los lectores consecutivos comparten el acceso; un escritor espera a que salgan
 * los lectores activos y bloquea a quienes llegaron después de él. Así ni los escritores
 * (mutaciones, mantenimiento) ni los lectores (búsquedas) pueden sufrir inanición.
 *
 * <p>Las esperas ignoran las interrupciones (se restaura el indicador al terminar): abandonar
 * un turno ya tomado bloquearía a todos los que vienen detrás.
 */
final class ReadWriteGuard {
    private long nextTicket;
    private long serving;
    private int readers;
    private boolean writing;

    void acquireRead() {
        boolean interrupted = false;
        synchronized (this) {
            long ticket = nextTicket++;
            while (ticket != serving || writing) {
                interrupted |= await();
            }
            readers++;
            serving++;
            notifyAll();
        }
        restore(interrupted);
    }

    synchronized void releaseRead() {
        if (readers <= 0) {
            throw new IllegalStateException("No hay lectores activos");
        }
        readers--;
        notifyAll();
    }

    void acquireWrite() {
        boolean interrupted = false;
        synchronized (this) {
            long ticket = nextTicket++;
            while (ticket != serving || writing || readers > 0) {
                interrupted |= await();
            }
            writing = true;
            serving++;
            notifyAll();
        }
        restore(interrupted);
    }

    synchronized void releaseWrite() {
        if (!writing) {
            throw new IllegalStateException("No hay escritor activo");
        }
        writing = false;
        notifyAll();
    }

    private boolean await() {
        try {
            wait();
            return false;
        } catch (InterruptedException e) {
            return true;
        }
    }

    private static void restore(boolean interrupted) {
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
