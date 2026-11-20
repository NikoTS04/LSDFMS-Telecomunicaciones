package com.hpc.lsdfms.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Descubrimiento no recursivo; el identificador se interpreta numéricamente. */
public final class DatasetFiles {
    private static final Pattern NAME = Pattern.compile("F_([1-9][0-9]*)\\.dat");

    public record Source(int id, Path path) { }

    private DatasetFiles() { }

    public static List<Source> list(Path directory) throws IOException {
        List<Source> sources = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            for (Path path : paths.toList()) {
                var match = NAME.matcher(path.getFileName().toString());
                if (!match.matches()) {
                    continue;
                }
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Archivo fuente no regular: " + path);
                }
                try {
                    sources.add(new Source(Integer.parseInt(match.group(1)), path));
                } catch (NumberFormatException e) {
                    throw new IOException("fileId fuera de int32: " + path, e);
                }
            }
        }
        if (sources.isEmpty()) {
            throw new IOException("No hay archivos F_<id>.dat en " + directory);
        }
        sources.sort(Comparator.comparingInt(Source::id));
        return sources;
    }
}
