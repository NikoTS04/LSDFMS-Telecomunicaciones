package com.hpc.lsdfms.core;

import com.hpc.lsdfms.model.IndexEntry;
import java.io.IOException;
import java.io.InterruptedIOException;

/** Tuplas primitivas: solo se comparten rangos disjuntos entre trabajadores. */
final class IndexTuples {
    final long[] keys;
    final int[] fileIds;
    final long[] offsets;
    final int size;

    IndexTuples(int capacity) {
        size = capacity;
        keys = new long[capacity];
        fileIds = new int[capacity];
        offsets = new long[capacity];
    }

    static IndexTuples allocate(long count, int copies) throws IOException {
        long limit = Math.min(Integer.MAX_VALUE - 8L,
                Runtime.getRuntime().maxMemory() / 4 / (IndexEntry.BYTES * copies));
        if (count < 0 || count > limit) {
            throw new IOException("Índice excede el presupuesto en memoria (" + limit
                    + " entradas); requiere ordenación externa");
        }
        return new IndexTuples((int) count);
    }

    void put(int position, long phone, int fileId, long offset) throws IOException {
        if (position < 0 || position >= size) {
            throw new IOException("El dataset creció durante INDEX");
        }
        keys[position] = phone;
        fileIds[position] = fileId;
        offsets[position] = offset;
    }

    /** Heapsort O(n log n), sin recursión ni objetos por registro. */
    void sort(int from, int to) throws IOException {
        int length = to - from;
        for (int i = length / 2 - 1; i >= 0; i--) {
            if ((i & 4095) == 0) {
                checkInterrupted();
            }
            sift(from, i, length);
        }
        for (int end = length - 1; end > 0; end--) {
            if ((end & 4095) == 0) {
                checkInterrupted();
            }
            swap(from, from + end);
            sift(from, 0, end);
        }
        checkInterrupted();
    }

    void merge(int from, int middle, int to, IndexTuples target) throws IOException {
        int left = from;
        int right = middle;
        for (int out = from; out < to; out++) {
            if ((out & 4095) == 0) {
                checkInterrupted();
            }
            int source = right == to || (left < middle && compare(left, right) <= 0)
                    ? left++ : right++;
            target.keys[out] = keys[source];
            target.fileIds[out] = fileIds[source];
            target.offsets[out] = offsets[source];
        }
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("INDEX interrumpido");
        }
    }

    private void sift(int base, int root, int end) {
        while (root < end / 2) {
            int child = root * 2 + 1;
            if (child + 1 < end && compare(base + child, base + child + 1) < 0) {
                child++;
            }
            if (compare(base + root, base + child) >= 0) {
                return;
            }
            swap(base + root, base + child);
            root = child;
        }
    }

    private int compare(int a, int b) {
        int result = Long.compare(keys[a], keys[b]);
        if (result == 0) {
            result = Integer.compare(fileIds[a], fileIds[b]);
        }
        return result == 0 ? Long.compare(offsets[a], offsets[b]) : result;
    }

    private void swap(int a, int b) {
        long key = keys[a];
        keys[a] = keys[b];
        keys[b] = key;
        int fileId = fileIds[a];
        fileIds[a] = fileIds[b];
        fileIds[b] = fileId;
        long offset = offsets[a];
        offsets[a] = offsets[b];
        offsets[b] = offset;
    }
}
