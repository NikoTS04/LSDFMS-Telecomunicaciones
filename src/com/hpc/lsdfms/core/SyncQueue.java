package com.hpc.lsdfms.core;

/**
 * Cola FIFO acotada, implementada como monitor con {@code synchronized}, {@code wait} y
 * {@code notifyAll}. Es segura para varios productores y consumidores.
 *
 * <p>Tras {@link #close()}, {@link #put} falla y {@link #take} entrega los elementos que
 * quedaban y después devuelve {@code null}. Las interrupciones se propagan como
 * {@link InterruptedException}; el invocador decide si restaura el indicador.
 */
public final class SyncQueue<T> {
    private final Object[] items;
    private int head;
    private int size;
    private boolean closed;

    public SyncQueue(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("La capacidad debe ser positiva: " + capacity);
        }
        this.items = new Object[capacity];
    }

    /** Encola un elemento; bloquea mientras la cola esté llena. */
    public synchronized void put(T item) throws InterruptedException {
        if (item == null) {
            throw new NullPointerException("No se admiten elementos nulos");
        }
        while (size == items.length && !closed) {
            wait();
        }
        if (closed) {
            throw new IllegalStateException("La cola está cerrada");
        }
        items[(head + size) % items.length] = item;
        size++;
        notifyAll();
    }

    /**
     * Extrae el elemento más antiguo; bloquea mientras la cola esté vacía y abierta.
     *
     * @return el elemento, o {@code null} si la cola está cerrada y vacía
     */
    @SuppressWarnings("unchecked")
    public synchronized T take() throws InterruptedException {
        while (size == 0 && !closed) {
            wait();
        }
        if (size == 0) {
            return null;
        }
        T item = (T) items[head];
        items[head] = null;
        head = (head + 1) % items.length;
        size--;
        notifyAll();
        return item;
    }

    /** Cierra la cola y despierta a todos los hilos bloqueados. Es idempotente. */
    public synchronized void close() {
        closed = true;
        notifyAll();
    }

    public synchronized boolean isClosed() {
        return closed;
    }

    public synchronized int size() {
        return size;
    }

    public int capacity() {
        return items.length;
    }
}
