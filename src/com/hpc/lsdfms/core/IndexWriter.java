package com.hpc.lsdfms.core;

import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.IndexEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Publicación compartida por los motores, invocada bajo bloqueo de dataset. */
final class IndexWriter {
    @FunctionalInterface
    interface Replacement {
        void replace(Path temporary, Path target) throws IOException;
    }

    private IndexWriter() { }

    static void publish(Path directory, IndexTuples tuples, Replacement replacement)
            throws IOException {
        Path temporary = Files.createTempFile(directory, ".index-phone-", ".tmp");
        try {
            try (FileChannel output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(64 * 1024)
                        .order(CdrFormat.BYTE_ORDER);
                for (int i = 0; i < tuples.size; i++) {
                    if ((i & 4095) == 0) {
                        IndexTuples.checkInterrupted();
                    }
                    if (buffer.remaining() < IndexEntry.BYTES) {
                        flush(output, buffer);
                    }
                    IndexEntry.write(buffer, tuples.keys[i], tuples.fileIds[i], tuples.offsets[i]);
                }
                flush(output, buffer);
                output.force(true);
            }
            IndexTuples.checkInterrupted();
            replacement.replace(temporary, directory.resolve(SequentialEngine.INDEX_NAME));
        } finally {
            Files.deleteIfExists(temporary);
        }
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
}
