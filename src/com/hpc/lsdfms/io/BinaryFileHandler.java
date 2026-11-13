package com.hpc.lsdfms.io;

import com.hpc.lsdfms.model.CdrFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Escaneo de cabeceras con memoria constante, sin materializar los payloads. */
public final class BinaryFileHandler {
    private BinaryFileHandler() { }

    public record FileStats(long bytes, long records, long deleted) {
        public long active() {
            return records - deleted;
        }
    }

    /** Valida estructura y límites; no valida el contenido UTF-8 del payload. */
    public static FileStats inspect(Path path) throws IOException {
        ByteBuffer header = ByteBuffer.allocateDirect(CdrFormat.HEADER_BYTES)
                .order(CdrFormat.BYTE_ORDER);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            long offset = 0;
            long records = 0;
            long deleted = 0;
            while (offset < size) {
                if (size - offset < CdrFormat.HEADER_BYTES) {
                    throw invalid(path, offset, "cabecera truncada");
                }
                header.clear();
                channel.position(offset);
                while (header.hasRemaining()) {
                    if (channel.read(header) < 0) {
                        throw invalid(path, offset, "fin de archivo inesperado");
                    }
                }
                header.flip();
                byte flag = header.get();
                int length = header.getInt();
                if (flag != 0 && flag != 1) {
                    throw invalid(path, offset, "isDeleted debe ser 0 o 1");
                }
                if (length < 0 || length > size - offset - CdrFormat.HEADER_BYTES) {
                    throw invalid(path, offset, "longitud de payload inválida o payload truncado");
                }
                records++;
                deleted += flag;
                offset += CdrFormat.HEADER_BYTES + (long) length;
            }
            return new FileStats(size, records, deleted);
        }
    }

    private static IOException invalid(Path path, long offset, String reason) {
        return new IOException(path + " @ byte " + offset + ": " + reason);
    }
}
