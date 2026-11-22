package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.FileCompactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
        IndexTuples tuples = IndexTuples.allocate(count, 1);
        int[] position = {0};
        for (var source : sources) {
            BinaryFileHandler.scan(source.path(),
                    (phone, offset) -> tuples.put(position[0]++, phone, source.id(), offset));
        }
        if (position[0] != count) {
            throw new IOException("El dataset cambió durante INDEX");
        }
        tuples.sort(0, tuples.size);
        IndexWriter.publish(directory, tuples, operations::replace);
        return count;
    }
}
