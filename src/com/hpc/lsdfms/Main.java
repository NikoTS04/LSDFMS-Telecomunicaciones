package com.hpc.lsdfms;

import com.hpc.lsdfms.io.BinaryFileHandler;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** CLI inicial. INDEX y PACK se incorporarán en los siguientes hitos. */
public final class Main {
    private Main() { }

    public static void main(String[] args) {
        if (args.length == 0 || (args.length == 1 && "--help".equals(args[0]))) {
            System.out.println("Uso: java -cp build/classes com.hpc.lsdfms.Main inspect <directorio>");
            return;
        }
        if (args.length != 2 || !"inspect".equals(args[0])) {
            System.err.println("Comando inválido. Usa --help. INDEX y PACK aún no están implementados.");
            System.exit(2);
            return;
        }
        try {
            inspect(Path.of(args[1]));
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void inspect(Path directory) throws IOException {
        List<Path> files;
        try (Stream<Path> paths = Files.list(directory)) {
            files = paths.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().matches("F_[1-9][0-9]*\\.dat"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (files.isEmpty()) {
            throw new IOException("No hay archivos F_<id>.dat en " + directory);
        }
        long bytes = 0;
        long records = 0;
        long deleted = 0;
        System.out.println("Archivo\tBytes\tRegistros\tActivos\tBorrados");
        for (Path file : files) {
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
