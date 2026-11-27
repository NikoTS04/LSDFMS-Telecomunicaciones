package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.FileCompactor;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
            return maintainLocked(access, recovery);
        }
    }

    /**
     * PACK (o recover) con el acceso ya en poder del llamador, por ejemplo un servicio en proceso.
     * Reconstruye también los tres índices de columna antes de cerrar el marcador.
     */
    public PackStats packLocked(DatasetAccess access, boolean recovery) throws IOException {
        return maintainLocked(access, recovery);
    }

    private PackStats maintainLocked(DatasetAccess access, boolean recovery) throws IOException {
        return PackMaintenance.execute(access, recovery, DatasetFiles.list(access.directory()),
                operations, PackMaintenance::sequential, (root, sources) ->
                        new ColumnIndexBuilder(new ParallelEngine.Scanner() { }, operations)
                                .buildLocked(root, sources));
    }

    /** Llamador posee el bloqueo; también se usa mientras el marcador sigue activo. */
    private long indexLocked(Path directory) throws IOException {
        var sources = DatasetFiles.list(directory);
        long count = 0;
        for (var source : sources) {
            count += BinaryFileHandler.inspect(source.path()).active();
        }
        if (ExternalIndex.required(count, 1)) {
            return ExternalIndex.build(directory, sources, count, 1,
                    new ParallelEngine.Scanner() { }, PackMaintenance::sequential, operations::replace);
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
