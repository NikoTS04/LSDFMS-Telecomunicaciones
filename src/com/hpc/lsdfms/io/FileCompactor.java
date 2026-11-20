package com.hpc.lsdfms.io;

import com.hpc.lsdfms.model.CdrFormat;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Compactación de un archivo a un temporal creado por el llamador bajo exclusión. */
public final class FileCompactor {
    private FileCompactor() { }

    public static void compact(Path source, Path temporary) throws IOException {
        long[] copied = {0};
        BinaryFileHandler.FileStats stats;
        try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS);
                FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                        StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
            stats = BinaryFileHandler.scanRecords(source, (deleted, length, phone, offset) -> {
                if (!deleted) {
                    long bytes = (long) CdrFormat.HEADER_BYTES + length;
                    FileTransfers.copy(input, output, offset, bytes);
                    copied[0] += bytes;
                }
            });
            output.force(true);
        }
        var packed = BinaryFileHandler.inspect(temporary);
        if (packed.deleted() != 0 || packed.records() != stats.active()
                || packed.bytes() != copied[0]) {
            throw new IOException("Validación del temporal de PACK fallida: " + temporary);
        }
    }
}
