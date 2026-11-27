package com.hpc.lsdfms.search;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.ColumnIndex;
import com.hpc.lsdfms.model.IndexEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Búsqueda exacta (lo == hi) o por rango cerrado [lo, hi] sobre el índice de una columna,
 * con lowerBound/upperBound en disco (O(log n)) y entrega incremental de coincidencias.
 *
 * <p>Cada coincidencia se revalida contra la cabecera del registro. Los registros borrados
 * lógicamente después de construir el índice se omiten (no cuentan ni producen error); una
 * clave que ya no coincide con la del índice indica un índice obsoleto y falla con
 * {@link IOException}.
 */
public final class ColumnSearch {
    public static final long NO_LIMIT = Long.MAX_VALUE;

    /** Coincidencia activa con los campos de cabecera. */
    public record Hit(int fileId, long offset, long phone, long timestamp, int payloadLen) { }

    @FunctionalInterface
    public interface HitConsumer {
        void accept(Hit hit) throws IOException;
    }

    /** {@code matches}: coincidencias entregadas; {@code truncated}: había más activas que el límite. */
    public record Result(long matches, boolean truncated) { }

    /** Adquiere el bloqueo del dataset (uso desde la CLI). */
    public Result search(Path directory, ColumnIndex column, long lo, long hi, long limit,
            HitConsumer consumer) throws IOException {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            return searchLocked(access, column, lo, hi, limit, consumer);
        }
    }

    /** El llamador ya posee el acceso (por ejemplo un servicio en proceso). */
    public Result searchLocked(DatasetAccess access, ColumnIndex column, long lo, long hi,
            long limit, HitConsumer consumer) throws IOException {
        if (lo > hi) {
            throw new IllegalArgumentException("El rango es inválido: lo > hi");
        }
        if (limit < 1) {
            throw new IllegalArgumentException("El límite debe ser al menos 1");
        }
        access.requireReady();
        Path directory = access.directory();
        try (FileChannel index = FileChannel.open(directory.resolve(column.fileName()),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                OpenFiles files = new OpenFiles()) {
            long size = index.size();
            if (size % IndexEntry.BYTES != 0) {
                throw new IOException("Índice truncado: tamaño no múltiplo de 20");
            }
            long entries = size / IndexEntry.BYTES;
            ByteBuffer buffer = ByteBuffer.allocate(IndexEntry.BYTES).order(CdrFormat.BYTE_ORDER);
            long end = bound(index, buffer, entries, hi, true);
            long delivered = 0;
            for (long i = bound(index, buffer, entries, lo, false); i < end; i++) {
                Hit hit = activeHit(directory, files, column, index, buffer, i);
                if (hit == null) {
                    continue;
                }
                if (delivered == limit) {
                    return new Result(delivered, true);
                }
                consumer.accept(hit);
                delivered++;
            }
            return new Result(delivered, false);
        }
    }

    private static Hit activeHit(Path directory, OpenFiles files,
            ColumnIndex column, FileChannel index, ByteBuffer buffer, long entry)
            throws IOException {
        readEntry(index, buffer, entry);
        long key = buffer.getLong();
        int fileId = buffer.getInt();
        long offset = buffer.getLong();
        if (fileId <= 0 || offset < 0) {
            throw new IOException("Entrada de índice inválida @ entrada " + entry);
        }
        Path path = directory.resolve("F_" + fileId + ".dat");
        var header = BinaryFileHandler.readHeader(files.channel(fileId, path), path, offset);
        if (header.deleted()) {
            return null;
        }
        long actual = switch (column) {
            case PHONE -> header.phoneNumber();
            case TIMESTAMP -> header.timestamp();
            case SIZE -> header.payloadLength();
        };
        if (actual != key) {
            throw new IOException(path + " @ byte " + offset
                    + ": referencia obsoleta: la clave del índice no coincide");
        }
        return new Hit(fileId, offset, header.phoneNumber(), header.timestamp(),
                header.payloadLength());
    }

    /** Canales de datos abiertos bajo demanda y cerrados todos al terminar la consulta. */
    private static final class OpenFiles implements AutoCloseable {
        private final Map<Integer, FileChannel> channels = new HashMap<>();

        FileChannel channel(int fileId, Path path) throws IOException {
            FileChannel channel = channels.get(fileId);
            if (channel == null) {
                channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                channels.put(fileId, channel);
            }
            return channel;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            for (FileChannel channel : channels.values()) {
                try {
                    channel.close();
                } catch (IOException e) {
                    if (failure == null) {
                        failure = e;
                    } else {
                        failure.addSuppressed(e);
                    }
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /** lowerBound (primera clave >= value) o upperBound (primera clave > value). */
    private static long bound(FileChannel index, ByteBuffer buffer, long entries, long value,
            boolean upper) throws IOException {
        long low = 0;
        long high = entries;
        while (low < high) {
            long mid = low + (high - low) / 2;
            readEntry(index, buffer, mid);
            long key = buffer.getLong();
            if (key < value || (upper && key == value)) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    private static void readEntry(FileChannel index, ByteBuffer buffer, long entry)
            throws IOException {
        buffer.clear();
        BinaryFileHandler.readFully(index, buffer, entry * IndexEntry.BYTES);
        buffer.flip();
    }
}
