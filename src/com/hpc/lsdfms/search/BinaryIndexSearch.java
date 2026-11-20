package com.hpc.lsdfms.search;

import com.hpc.lsdfms.core.SequentialEngine;
import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.IndexEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Búsqueda de rango en disco O(log n), entrega incremental de k coincidencias. */
public final class BinaryIndexSearch {
    @FunctionalInterface
    public interface RecordConsumer {
        void accept(int fileId, long offset, BinaryFileHandler.CdrRecord record) throws IOException;
    }

    public long search(Path directory, long phone, RecordConsumer consumer) throws IOException {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            access.requireReady();
            return searchLocked(access.directory(), phone, consumer);
        }
    }

    private long searchLocked(Path directory, long phone, RecordConsumer consumer) throws IOException {
        try (FileChannel index = FileChannel.open(directory.resolve(SequentialEngine.INDEX_NAME),
                StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long size = index.size();
            if (size % IndexEntry.BYTES != 0) {
                throw new IOException("Índice truncado: tamaño no múltiplo de 20");
            }
            long entries = size / IndexEntry.BYTES;
            ByteBuffer buffer = ByteBuffer.allocate(IndexEntry.BYTES).order(CdrFormat.BYTE_ORDER);
            long first = bound(index, buffer, entries, phone, false);
            long end = bound(index, buffer, entries, phone, true);
            for (long i = first; i < end; i++) {
                readEntry(index, buffer, i);
                long actual = buffer.getLong();
                int fileId = buffer.getInt();
                long offset = buffer.getLong();
                if (actual != phone || fileId <= 0 || offset < 0) {
                    throw new IOException("Entrada de índice inválida @ entrada " + i);
                }
                var record = BinaryFileHandler.readActive(
                        directory.resolve("F_" + fileId + ".dat"), offset, phone);
                consumer.accept(fileId, offset, record);
            }
            return end - first;
        }
    }

    private static long bound(FileChannel index, ByteBuffer buffer, long entries,
            long phone, boolean upper) throws IOException {
        long low = 0;
        long high = entries;
        while (low < high) {
            long mid = low + (high - low) / 2;
            readEntry(index, buffer, mid);
            long key = buffer.getLong();
            if (key < phone || (upper && key == phone)) {
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
