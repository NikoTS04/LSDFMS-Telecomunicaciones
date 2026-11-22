package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.DatasetFiles;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/** Protocolo único de PACK, independiente del modo de ejecución de las tareas. */
final class PackMaintenance {
    @FunctionalInterface
    interface Runner {
        void run(List<Callable<Void>> tasks) throws IOException;
    }

    @FunctionalInterface
    interface Indexer {
        long index(Path directory, List<DatasetFiles.Source> sources) throws IOException;
    }

    private PackMaintenance() { }

    static SequentialEngine.PackStats execute(DatasetAccess access, boolean recovery,
            List<DatasetFiles.Source> sources, SequentialEngine.Operations operations,
            Runner runner, Indexer indexer) throws IOException {
        if (recovery) {
            access.requireRecovery();
        } else {
            access.requireReady();
        }
        Path directory = access.directory();
        BinaryFileHandler.FileStats[] stats = new BinaryFileHandler.FileStats[sources.size()];
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            int file = i;
            tasks.add(() -> {
                stats[file] = BinaryFileHandler.inspect(sources.get(file).path());
                return null;
            });
        }
        runner.run(tasks);
        long before = 0;
        long active = 0;
        for (var stat : stats) {
            before += stat.bytes();
            active += stat.active();
        }
        IndexTuples.checkInterrupted();
        if (!recovery) {
            access.beginMaintenance();
        }
        Path index = directory.resolve(SequentialEngine.INDEX_NAME);
        if (Files.exists(index, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(index, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Destino de índice no regular: " + index);
        }
        Files.deleteIfExists(index);
        long[] sizes = new long[sources.size()];
        tasks.clear();
        for (int i = 0; i < sources.size(); i++) {
            int file = i;
            var source = sources.get(i);
            tasks.add(() -> {
                IndexTuples.checkInterrupted();
                Path temporary = Files.createTempFile(directory,
                        ".lsdfms-pack-F_" + source.id() + "-", ".tmp");
                try {
                    operations.compact(source.path(), temporary);
                    IndexTuples.checkInterrupted();
                    sizes[file] = Files.size(temporary);
                    operations.replace(temporary, source.path());
                } finally {
                    Files.deleteIfExists(temporary);
                }
                return null;
            });
        }
        runner.run(tasks);
        long after = 0;
        for (long size : sizes) {
            after += size;
        }
        IndexTuples.checkInterrupted();
        long indexed = indexer.index(directory, sources);
        if (indexed != active) {
            throw new IOException("Conteo de activos cambió durante PACK");
        }
        IndexTuples.checkInterrupted();
        access.finishMaintenance();
        return new SequentialEngine.PackStats(before, after, active);
    }

    static void sequential(List<Callable<Void>> tasks) throws IOException {
        for (var task : tasks) {
            try {
                task.call();
            } catch (IOException e) {
                throw e;
            } catch (Exception e) {
                throw new IOException("Falló una tarea de PACK", e);
            }
        }
    }
}
