package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.FileCompactor;
import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.IndexEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.LinkOption;

/** INDEX/PACK baseline, un hilo y exclusión cooperativa por dataset. */
public final class SequentialEngine {
    public static final String INDEX_NAME = "index_phone.idx";
    private final Operations operations;

    /** Punto de inyección de fallos de I/O, visible solo dentro del paquete. */
    interface Operations {
        default void compact(Path source, Path temporary) throws IOException {
            FileCompactor.compact(source, temporary);
        }

        default void replace(Path temporary, Path target) throws IOException {
            Files.move(temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public SequentialEngine() {
        this(new Operations() { });
    }

    SequentialEngine(Operations operations) {
        this.operations = operations;
    }

    public record PackStats(long bytesBefore, long bytesAfter, long activeRecords) {
        public long reclaimedBytes() {
            return bytesBefore - bytesAfter;
        }
    }

    public long index(Path directory) throws IOException {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            access.requireReady();
            return indexLocked(access.directory());
        }
    }

    public PackStats pack(Path directory) throws IOException {
        return maintain(directory, false);
    }

    public PackStats recover(Path directory) throws IOException {
        return maintain(directory, true);
    }

    private PackStats maintain(Path directory, boolean recovery) throws IOException {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            directory = access.directory();
            if (recovery) {
                access.requireRecovery();
            } else {
                access.requireReady();
            }
            var sources = DatasetFiles.list(directory);
            long before = 0;
            long active = 0;
            // Preflight completo antes de invalidar un índice existente.
            for (var source : sources) {
                var stats = BinaryFileHandler.inspect(source.path());
                before += stats.bytes();
                active += stats.active();
            }
            if (!recovery) {
                access.beginMaintenance();
            }
            Path index = directory.resolve(INDEX_NAME);
            if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Destino de índice no regular: " + index);
            }
            // El marcador persistente ya bloquea consultas incluso si esta eliminación falla.
            Files.deleteIfExists(index);
            long after = 0;
            for (var source : sources) {
                Path temporary = Files.createTempFile(directory,
                        ".lsdfms-pack-F_" + source.id() + "-", ".tmp");
                try {
                    operations.compact(source.path(), temporary);
                    after += Files.size(temporary);
                    operations.replace(temporary, source.path());
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            long indexed = indexLocked(directory);
            if (indexed != active) {
                throw new IOException("Conteo de activos cambió durante PACK");
            }
            access.finishMaintenance();
            return new PackStats(before, after, active);
        }
    }

    /** Llamador posee el bloqueo; también se usa mientras el marcador sigue activo. */
    private long indexLocked(Path directory) throws IOException {
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
            operations.replace(temporary, directory.resolve(INDEX_NAME));
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
