package com.hpc.lsdfms.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Exclusión cooperativa entre procesos y estado persistente de mantenimiento. */
public final class DatasetAccess implements AutoCloseable {
    public static final String LOCK_NAME = ".lsdfms.lock";
    public static final String MAINTENANCE_NAME = ".lsdfms-maintenance";
    // Evita abrir/cerrar otro descriptor del mismo lock en esta JVM: en POSIX
    // cerrar un descriptor puede liberar locks adquiridos por otro del proceso.
    private static final Set<Path> OWNED = ConcurrentHashMap.newKeySet();
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;
    private boolean closed;

    private DatasetAccess(Path directory, FileChannel channel, FileLock lock) {
        this.directory = directory;
        this.channel = channel;
        this.lock = lock;
    }

    public static DatasetAccess acquire(Path directory) throws IOException {
        Path root = directory.toRealPath();
        if (!OWNED.add(root)) {
            throw new IOException("Dataset ocupado en esta JVM: " + root);
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(root.resolve(LOCK_NAME),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            FileLock lock = channel.tryLock();
            if (lock == null) {
                throw new IOException("Dataset ocupado: " + root);
            }
            return new DatasetAccess(root, channel, lock);
        } catch (IOException | RuntimeException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeError) {
                    e.addSuppressed(closeError);
                }
            }
            OWNED.remove(root);
            if (e instanceof OverlappingFileLockException) {
                throw new IOException("Dataset ocupado en esta JVM: " + root, e);
            }
            throw e;
        }
    }

    public Path directory() {
        return directory;
    }

    public void requireReady() throws IOException {
        requireHeld();
        if (Files.exists(marker(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Mantenimiento pendiente; ejecuta recover: " + directory);
        }
    }

    public void requireRecovery() throws IOException {
        requireHeld();
        if (!Files.isRegularFile(marker(), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("No hay un marcador regular de mantenimiento: " + directory);
        }
    }

    public void beginMaintenance() throws IOException {
        requireReady();
        try (FileChannel state = FileChannel.open(marker(), StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = StandardCharsets.UTF_8.encode("LSDFMS PACK v1\n");
            while (bytes.hasRemaining()) {
                if (state.write(bytes) <= 0) {
                    throw new IOException("Escritura del marcador sin progreso");
                }
            }
            state.force(true);
        }
    }

    public void finishMaintenance() throws IOException {
        requireRecovery();
        Files.delete(marker());
    }

    private Path marker() {
        return directory.resolve(MAINTENANCE_NAME);
    }

    private void requireHeld() throws IOException {
        if (!lock.isValid()) {
            throw new IOException("El bloqueo del dataset ya está cerrado");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            lock.release();
        } finally {
            try {
                channel.close();
            } finally {
                OWNED.remove(directory);
            }
        }
    }
}
