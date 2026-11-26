package com.hpc.lsdfms;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.core.SequentialEngine;
import com.hpc.lsdfms.core.ParallelEngine;
import com.hpc.lsdfms.search.BinaryIndexSearch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** CLI de INDEX secuencial/paralelo y PACK con exclusión y recuperación. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            System.out.println("Uso: java -cp build/classes com.hpc.lsdfms.Main "
                    + "inspect|index|pack|recover <directorio>\n"
                    + "     java -cp build/classes com.hpc.lsdfms.Main "
                    + "search <directorio> <phoneNumber>\n"
                    + "     java -cp build/classes com.hpc.lsdfms.Main "
                    + "index-parallel|pack-parallel|recover-parallel <directorio> <trabajadores:1-256>\n"
                    + "Salidas: 0 éxito, 1 error de datos/I/O, 2 argumentos inválidos.\n"
                    + "Propiedades JVM: -Dlsdfms.index.mode=auto|memory|external\n"
                    + "  -Dlsdfms.index.memoryMiB=128 -Dlsdfms.index.fanIn=16\n"
                    + "Bloqueo exclusivo por dataset; recover completa mantenimiento pendiente.");
            return;
        }
        boolean valid = (args.length == 2
                && ("inspect".equals(args[0]) || "index".equals(args[0])
                        || "pack".equals(args[0]) || "recover".equals(args[0])))
                || (args.length == 3
                        && ("search".equals(args[0]) || "index-parallel".equals(args[0])
                                || "pack-parallel".equals(args[0])
                                || "recover-parallel".equals(args[0])));
        if (!valid) {
            System.err.println("Comando inválido. Usa --help.");
            System.exit(2);
            return;
        }
        try {
            Path directory = Path.of(args[1]);
            switch (args[0]) {
                case "inspect" -> inspect(directory);
                case "index" -> System.out.println("INDEX\t" + new SequentialEngine().index(directory));
                case "index-parallel" -> System.out.println("INDEX_PARALLEL\t"
                        + new ParallelEngine(Integer.parseInt(args[2])).index(directory));
                case "pack", "recover" -> {
                    SequentialEngine engine = new SequentialEngine();
                    var stats = "pack".equals(args[0]) ? engine.pack(directory) : engine.recover(directory);
                    System.out.printf("%s\t%d\t%d\t%d\t%d%n",
                            "pack".equals(args[0]) ? "PACK" : "RECOVER", stats.bytesBefore(),
                            stats.bytesAfter(), stats.reclaimedBytes(), stats.activeRecords());
                }
                case "pack-parallel", "recover-parallel" -> {
                    ParallelEngine engine = new ParallelEngine(Integer.parseInt(args[2]));
                    var stats = "pack-parallel".equals(args[0])
                            ? engine.pack(directory) : engine.recover(directory);
                    System.out.printf("%s\t%d\t%d\t%d\t%d%n",
                            "pack-parallel".equals(args[0]) ? "PACK_PARALLEL" : "RECOVER_PARALLEL",
                            stats.bytesBefore(), stats.bytesAfter(), stats.reclaimedBytes(),
                            stats.activeRecords());
                }
                case "search" -> search(directory, Long.parseLong(args[2]));
                default -> throw new IllegalArgumentException("Comando inválido");
            }
        } catch (IllegalArgumentException e) {
            System.err.println("Argumentos inválidos: " + e.getMessage());
            System.exit(2);
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void search(Path directory, long phone) throws IOException {
        System.out.println("Telefono\tFileId\tOffset\tTimestamp\tPayload");
        long matches = new BinaryIndexSearch().search(directory, phone, (fileId, offset, record) -> {
            String payload = new String(record.payload(), StandardCharsets.UTF_8)
                    .replace("\\", "\\\\").replace("\t", "\\t")
                    .replace("\r", "\\r").replace("\n", "\\n");
            System.out.printf("%d\t%d\t%d\t%d\t%s%n",
                    record.phoneNumber(), fileId, offset, record.timestamp(), payload);
        });
        System.out.println("TOTAL\t" + matches);
    }

    private static void inspect(Path directory) throws IOException {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            inspectLocked(access.directory());
        }
    }

    private static void inspectLocked(Path directory) throws IOException {
        var files = DatasetFiles.list(directory);
        long bytes = 0;
        long records = 0;
        long deleted = 0;
        System.out.println("Archivo\tBytes\tRegistros\tActivos\tBorrados");
        for (var source : files) {
            Path file = source.path();
            var stats = BinaryFileHandler.inspect(file);
            System.out.printf("%s\t%d\t%d\t%d\t%d%n", file.getFileName(), stats.bytes(),
                    stats.records(), stats.active(), stats.deleted());
            bytes += stats.bytes();
            records += stats.records();
            deleted += stats.deleted();
        }
        System.out.printf("TOTAL\t%d\t%d\t%d\t%d%n", bytes, records, records - deleted, deleted);
    }
}
