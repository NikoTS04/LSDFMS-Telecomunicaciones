package com.hpc.lsdfms.io;

import com.hpc.lsdfms.model.CdrFormat;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;

/** Escaneo de cabeceras con memoria constante, sin materializar los payloads. */
public final class BinaryFileHandler {
    private BinaryFileHandler() { }

    public record FileStats(long bytes, long records, long deleted) {
        public long active() {
            return records - deleted;
        }
    }

    @FunctionalInterface
    public interface ActiveRecordConsumer {
        void accept(long phoneNumber, long byteOffset) throws IOException;
    }

    @FunctionalInterface
    public interface RecordConsumer {
        void accept(boolean deleted, int payloadLength, long phoneNumber, long byteOffset)
                throws IOException;
    }

    /** Resultado individual de consulta; no se materializa durante INDEX. */
    public record CdrRecord(long phoneNumber, long timestamp, byte[] payload) { }

    /** Valida estructura y límites; no valida el contenido UTF-8 del payload. */
    public static FileStats inspect(Path path) throws IOException {
        return scan(path, (phone, offset) -> { });
    }

    /** Escanea cabeceras, omite payloads y emite únicamente registros activos. */
    public static FileStats scan(Path path, ActiveRecordConsumer consumer) throws IOException {
        return scanRecords(path, (deleted, length, phone, offset) -> {
            if (!deleted) {
                consumer.accept(phone, offset);
            }
        });
    }

    /** Emite metadatos sin asignar objetos por registro ni cargar payloads. */
    public static FileStats scanRecords(Path path, RecordConsumer consumer) throws IOException {
        ByteBuffer header = ByteBuffer.allocateDirect(CdrFormat.HEADER_BYTES)
                .order(CdrFormat.BYTE_ORDER);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            long offset = 0;
            long records = 0;
            long deleted = 0;
            while (offset < size) {
                if (size - offset < CdrFormat.HEADER_BYTES) {
                    throw invalid(path, offset, "cabecera truncada");
                }
                header.clear();
                readFully(channel, header, offset);
                header.flip();
                byte flag = header.get();
                int length = header.getInt();
                if (flag != 0 && flag != 1) {
                    throw invalid(path, offset, "isDeleted debe ser 0 o 1");
                }
                if (length < 0 || length > size - offset - CdrFormat.HEADER_BYTES) {
                    throw invalid(path, offset, "longitud de payload inválida o payload truncado");
                }
                consumer.accept(flag != 0, length, header.getLong(), offset);
                records++;
                deleted += flag;
                offset += CdrFormat.HEADER_BYTES + (long) length;
            }
            return new FileStats(size, records, deleted);
        }
    }

    /** Lee y verifica una referencia de índice antes de entregar su payload. */
    public static CdrRecord readActive(Path path, long offset, long expectedPhone)
            throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = channel.size();
            if (offset < 0 || offset > size || size - offset < CdrFormat.HEADER_BYTES) {
                throw invalid(path, offset, "offset fuera de límites o cabecera truncada");
            }
            ByteBuffer header = ByteBuffer.allocate(CdrFormat.HEADER_BYTES)
                    .order(CdrFormat.BYTE_ORDER);
            readFully(channel, header, offset);
            header.flip();
            byte flag = header.get();
            int length = header.getInt();
            long phone = header.getLong();
            long timestamp = header.getLong();
            if (flag != 0 || phone != expectedPhone) {
                throw invalid(path, offset, "referencia obsoleta: clave o estado no coincide");
            }
            if (length < 0 || length > size - offset - CdrFormat.HEADER_BYTES) {
                throw invalid(path, offset, "longitud de payload inválida o payload truncado");
            }
            if (length > Runtime.getRuntime().maxMemory() / 4) {
                throw invalid(path, offset, "payload excede el presupuesto de consulta");
            }
            byte[] payload = new byte[length];
            readFully(channel, ByteBuffer.wrap(payload), offset + CdrFormat.HEADER_BYTES);
            return new CdrRecord(phone, timestamp, payload);
        }
    }

    /** Lectura posicional completa; evita bucles infinitos si no hay progreso. */
    public static void readFully(FileChannel channel, ByteBuffer buffer, long position)
            throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read <= 0) {
                throw new IOException("Lectura incompleta o sin progreso @ byte " + position);
            }
            position += read;
        }
    }

    private static IOException invalid(Path path, long offset, String reason) {
        return new IOException(path + " @ byte " + offset + ": " + reason);
    }
}
