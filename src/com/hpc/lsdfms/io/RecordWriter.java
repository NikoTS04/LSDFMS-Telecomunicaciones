package com.hpc.lsdfms.io;

import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.CdrPayload;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

/**
 * Mutaciones puntuales sobre un dataset ya bloqueado: alta al final del último archivo,
 * baja lógica por cambio del flag y actualización como alta seguida de baja.
 *
 * <p>No adquiere ningún bloqueo propio: el {@link DatasetAccess} pertenece a quien lo
 * entrega y debe serializar las llamadas (esta clase no es segura entre hilos).
 */
public final class RecordWriter {
    /** Ubicación de un registro dentro del dataset. */
    public record Location(int fileId, long offset) { }

    private final DatasetAccess access;

    public RecordWriter(DatasetAccess access) {
        this.access = access;
    }

    /** Agrega un registro activo al final del último F_n.dat y fuerza el disco. */
    public Location append(long phone, long timestamp, byte[] payload) throws IOException {
        access.requireReady();
        if (payload == null || payload.length < CdrPayload.MIN_BYTES
                || payload.length > CdrPayload.MAX_BYTES) {
            throw new IllegalArgumentException("El payload debe medir entre "
                    + CdrPayload.MIN_BYTES + " y " + CdrPayload.MAX_BYTES + " bytes");
        }
        if (phone <= 0 || timestamp < 0) {
            throw new IllegalArgumentException("phone debe ser positivo y timestamp no negativo");
        }
        List<DatasetFiles.Source> sources = DatasetFiles.list(access.directory());
        DatasetFiles.Source last = sources.get(sources.size() - 1);
        ByteBuffer record = ByteBuffer.allocate(CdrFormat.HEADER_BYTES + payload.length)
                .order(CdrFormat.BYTE_ORDER);
        record.put((byte) 0).putInt(payload.length).putLong(phone).putLong(timestamp).put(payload);
        record.flip();
        try (FileChannel channel = FileChannel.open(last.path(), StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            long offset = channel.size();
            long position = offset;
            while (record.hasRemaining()) {
                int written = channel.write(record, position);
                if (written <= 0) {
                    throw new IOException(last.path() + ": escritura sin progreso @ byte " + position);
                }
                position += written;
            }
            channel.force(true);
            return new Location(last.id(), offset);
        }
    }

    /** Marca como borrado el registro activo en la ubicación indicada si su teléfono coincide. */
    public void markDeleted(int fileId, long offset, long expectedPhone) throws IOException {
        access.requireReady();
        Path path = fileFor(fileId);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            requireActive(channel, fileId, offset, expectedPhone);
            ByteBuffer flagByte = ByteBuffer.wrap(new byte[]{1});
            if (channel.write(flagByte, offset + CdrFormat.DELETED_OFFSET) != 1) {
                throw new IOException(path + ": escritura del flag sin progreso @ byte " + offset);
            }
            channel.force(true);
        }
    }

    /**
     * Reemplaza un registro: primero agrega el nuevo y luego borra el anterior, de modo que
     * un fallo intermedio deja un duplicado y nunca una pérdida. La referencia anterior se
     * valida antes de agregar para no dejar duplicados por entradas obsoletas.
     */
    public Location update(int fileId, long offset, long expectedPhone, long newPhone,
            long newTimestamp, byte[] newPayload) throws IOException {
        access.requireReady();
        try (FileChannel channel = FileChannel.open(fileFor(fileId), StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            requireActive(channel, fileId, offset, expectedPhone);
        }
        Location created = append(newPhone, newTimestamp, newPayload);
        markDeleted(fileId, offset, expectedPhone);
        return created;
    }


    /** Verifica límites, estructura, estado activo y teléfono del registro en {@code offset}. */
    private static void requireActive(FileChannel channel, int fileId, long offset,
            long expectedPhone) throws IOException {
            long size = channel.size();
            if (offset < 0 || size - offset < CdrFormat.HEADER_BYTES) {
                throw new IllegalArgumentException("Offset fuera de límites: F_" + fileId + ".dat @ "
                        + offset);
            }
            ByteBuffer header = ByteBuffer.allocate(CdrFormat.HEADER_BYTES)
                    .order(CdrFormat.BYTE_ORDER);
            BinaryFileHandler.readFully(channel, header, offset);
            header.flip();
            byte flag = header.get();
            int length = header.getInt();
            long phone = header.getLong();
            if ((flag != 0 && flag != 1) || length < 0
                    || length > size - offset - CdrFormat.HEADER_BYTES) {
                throw new IllegalArgumentException("El offset no apunta a un registro válido: F_"
                        + fileId + ".dat @ " + offset);
            }
            if (flag == 1) {
                throw new IllegalStateException("El registro ya está borrado: F_" + fileId
                        + ".dat @ " + offset);
            }
            if (phone != expectedPhone) {
                throw new IllegalStateException("El teléfono no coincide en F_" + fileId
                        + ".dat @ " + offset + " (referencia obsoleta)");
            }
    }

    private Path fileFor(int fileId) throws IOException {
        for (DatasetFiles.Source source : DatasetFiles.list(access.directory())) {
            if (source.id() == fileId) {
                return source.path();
            }
        }
        throw new IllegalArgumentException("No existe F_" + fileId + ".dat");
    }
}
