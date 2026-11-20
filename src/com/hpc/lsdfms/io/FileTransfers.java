package com.hpc.lsdfms.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/** Copia rangos completos sin depender de que transferTo transfiera todo de una vez. */
public final class FileTransfers {
    private static final int BUFFER_BYTES = 64 * 1024;
    private static final long TRANSFER_BYTES = 8L * 1024 * 1024;

    @FunctionalInterface
    interface Transfer {
        long transfer(FileChannel source, long position, long count, FileChannel target)
                throws IOException;
    }

    private FileTransfers() { }

    public static void copy(FileChannel source, FileChannel target, long position, long count)
            throws IOException {
        copy(source, target, position, count, FileChannel::transferTo);
    }

    static void copy(FileChannel source, FileChannel target, long position, long count,
            Transfer transfer) throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Copia interrumpida");
        }
        if (position < 0 || count < 0 || position > source.size()
                || count > source.size() - position) {
            throw new IOException("Rango de transferencia fuera de límites");
        }
        ByteBuffer fallback = null;
        while (count > 0) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Copia interrumpida");
            }
            long request = Math.min(count, TRANSFER_BYTES);
            long copied = transfer.transfer(source, position, request, target);
            if (copied < 0 || copied > request) {
                throw new IOException("Resultado de transferencia inválido");
            }
            if (copied == 0) {
                if (fallback == null) {
                    fallback = ByteBuffer.allocateDirect(BUFFER_BYTES);
                }
                fallback.clear();
                fallback.limit((int) Math.min(count, BUFFER_BYTES));
                BinaryFileHandler.readFully(source, fallback, position);
                fallback.flip();
                copied = fallback.remaining();
                while (fallback.hasRemaining()) {
                    if (target.write(fallback) <= 0) {
                        throw new IOException("Copia sin progreso de escritura");
                    }
                }
            }
            position += copied;
            count -= copied;
        }
    }
}
