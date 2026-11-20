package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.IndexEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** INDEX baseline, un hilo, dataset inmutable y tuplas primitivas en memoria. */
public final class SequentialEngine {
    public static final String INDEX_NAME = "index_phone.idx";

    public long index(Path directory) throws IOException {
        var sources = DatasetFiles.list(directory);
        long count = 0;
        for (var source : sources) {
            count += BinaryFileHandler.inspect(source.path()).active();
        }
        // Reserva amplia para JVM, buffers y cliente; sin crecimiento de arreglos.
        long limit = Math.min(Integer.MAX_VALUE - 8L,
                Runtime.getRuntime().maxMemory() / 4 / IndexEntry.BYTES);
        if (count > limit) {
            throw new IOException("Índice excede el presupuesto en memoria (" + limit
                    + " entradas); requiere ordenación externa");
        }
        Tuples tuples = new Tuples((int) count);
        for (var source : sources) {
            BinaryFileHandler.scan(source.path(),
                    (phone, offset) -> tuples.add(phone, source.id(), offset));
        }
        if (tuples.size != count) {
            throw new IOException("El dataset cambió durante INDEX");
        }
        tuples.sort();
        Path temporary = Files.createTempFile(directory, ".index-phone-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024)
                        .order(CdrFormat.BYTE_ORDER);
                for (int i = 0; i < tuples.size; i++) {
                    if (buffer.remaining() < IndexEntry.BYTES) {
                        flush(output, buffer);
                    }
                    IndexEntry.write(buffer, tuples.keys[i], tuples.fileIds[i], tuples.offsets[i]);
                }
                flush(output, buffer);
                output.force(true);
            }
            Files.move(temporary, directory.resolve(INDEX_NAME),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return count;
    }

    private static void flush(FileChannel output, ByteBuffer buffer) throws IOException {
        buffer.flip();
        while (buffer.hasRemaining()) {
            if (output.write(buffer) <= 0) {
                throw new IOException("Escritura del índice sin progreso");
            }
        }
        buffer.clear();
    }

    /** Heapsort in-place O(n log n), sin recursión ni objetos por entrada. */
    private static final class Tuples {
        private final long[] keys;
        private final int[] fileIds;
        private final long[] offsets;
        private int size;

        Tuples(int capacity) {
            keys = new long[capacity];
            fileIds = new int[capacity];
            offsets = new long[capacity];
        }

        void add(long phone, int fileId, long offset) throws IOException {
            if (size == keys.length) {
                throw new IOException("El dataset creció durante INDEX");
            }
            keys[size] = phone;
            fileIds[size] = fileId;
            offsets[size++] = offset;
        }

        void sort() {
            for (int i = size / 2 - 1; i >= 0; i--) {
                sift(i, size);
            }
            for (int end = size - 1; end > 0; end--) {
                swap(0, end);
                sift(0, end);
            }
        }

        private void sift(int root, int end) {
            while (root < end / 2) {
                int child = root * 2 + 1;
                if (child + 1 < end && compare(child, child + 1) < 0) {
                    child++;
                }
                if (compare(root, child) >= 0) {
                    return;
                }
                swap(root, child);
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
}
