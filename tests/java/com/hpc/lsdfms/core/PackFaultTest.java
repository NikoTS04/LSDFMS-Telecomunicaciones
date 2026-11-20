package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.search.BinaryIndexSearch;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Inyección determinista de fallos sin opciones de prueba en la CLI productiva. */
public final class PackFaultTest {
    private PackFaultTest() { }

    public static void main(String[] args) throws Exception {
        Path locking = Files.createDirectory(Path.of(args[0]).resolve("lock-lifecycle"));
        DatasetAccess oldAccess = DatasetAccess.acquire(locking);
        oldAccess.close();
        try (DatasetAccess current = DatasetAccess.acquire(locking)) {
            oldAccess.close(); // No debe retirar el dueño actual del registro de locks JVM.
            try {
                DatasetAccess unexpected = DatasetAccess.acquire(locking);
                unexpected.close();
                throw new AssertionError("Doble cierre no debe permitir otro dueño JVM");
            } catch (IOException expected) {
                check(expected.getMessage().contains("ocupado"), "Conserva dueño JVM");
            }
            checkNativeLock(current.directory());
        }
        for (String mode : new String[]{"copy", "before", "after", "index", "atomic", "interrupt"}) {
            Path directory = Files.createDirectory(Path.of(args[0]).resolve(mode));
            byte[] original = fixture();
            Files.write(directory.resolve("F_1.dat"), original);
            Files.write(directory.resolve("F_3.dat"), original);
            new SequentialEngine().index(directory);
            byte[] oldIndex = Files.readAllBytes(directory.resolve(SequentialEngine.INDEX_NAME));
            SequentialEngine engine = new SequentialEngine(new SequentialEngine.Operations() {
                @Override
                public void compact(Path source, Path temporary) throws IOException {
                    try {
                        new SequentialEngine().index(directory);
                        throw new AssertionError("Debe excluir reentrada en la misma JVM");
                    } catch (IOException expected) {
                        check(expected.getMessage().contains("ocupado"), "Bloqueo JVM");
                    }
                    checkNativeLock(directory);
                    check(Files.exists(directory.resolve(DatasetAccess.MAINTENANCE_NAME)),
                            "Debe persistir el marcador antes de copiar");
                    check(!Files.exists(directory.resolve(SequentialEngine.INDEX_NAME)),
                            "Debe invalidarse el índice antes de copiar");
                    if ("copy".equals(mode)) {
                        Files.write(temporary, new byte[]{1, 2});
                        throw new IOException("No space left on device (simulado)");
                    }
                    if ("interrupt".equals(mode)) {
                        throw new InterruptedIOException("Interrupción simulada");
                    }
                    SequentialEngine.Operations.super.compact(source, temporary);
                }

                @Override
                public void replace(Path temporary, Path target) throws IOException {
                    boolean data = target.getFileName().toString().endsWith(".dat");
                    if ("before".equals(mode) && data) {
                        throw new IOException("Fallo antes del reemplazo");
                    }
                    if ("atomic".equals(mode) && data) {
                        throw new AtomicMoveNotSupportedException(
                                temporary.toString(), target.toString(), "simulado");
                    }
                    if ("index".equals(mode) && !data) {
                        throw new IOException("Fallo al publicar el índice nuevo");
                    }
                    SequentialEngine.Operations.super.replace(temporary, target);
                    if ("after".equals(mode) && data) {
                        throw new IOException("Fallo después del primer reemplazo");
                    }
                }
            });
            try {
                engine.pack(directory);
                throw new AssertionError("PACK debe fallar: " + mode);
            } catch (IOException expected) {
                check(Files.exists(directory.resolve(DatasetAccess.MAINTENANCE_NAME)),
                        "El marcador debe conservarse: " + mode);
                check(!Files.exists(directory.resolve(SequentialEngine.INDEX_NAME)),
                        "No debe publicarse un índice tras el error: " + mode);
            }
            byte[] packed = Arrays.copyOfRange(original, 24, original.length);
            boolean firstMoved = "after".equals(mode) || "index".equals(mode);
            check(Arrays.equals(Files.readAllBytes(directory.resolve("F_1.dat")),
                    firstMoved ? packed : original), "Estado del primer original: " + mode);
            check(Arrays.equals(Files.readAllBytes(directory.resolve("F_3.dat")),
                    "index".equals(mode) ? packed : original), "Estado del segundo original");
            try (var paths = Files.list(directory)) {
                check(paths.noneMatch(path -> path.toString().endsWith(".tmp")),
                        "No deben quedar temporales de la ejecución");
            }
            // Aun si un índice antiguo reaparece, no puede utilizarse con el marcador.
            Files.write(directory.resolve(SequentialEngine.INDEX_NAME), oldIndex);
            try {
                new BinaryIndexSearch().search(directory, 7, (id, offset, record) -> {
                    throw new AssertionError("No debe entregar registros obsoletos");
                });
                throw new AssertionError("La búsqueda debe rechazar el marcador");
            } catch (IOException expected) {
                check(expected.getMessage().contains("recover"), "Debe solicitar recuperación");
            }
            try {
                new SequentialEngine().index(directory);
                throw new AssertionError("INDEX no debe omitir la recuperación");
            } catch (IOException expected) {
                check(expected.getMessage().contains("recover"), "INDEX debe rechazar marcador");
            }
            new SequentialEngine().recover(directory);
            check(Arrays.equals(Files.readAllBytes(directory.resolve("F_1.dat")), packed),
                    "Primer archivo recuperado");
            check(Arrays.equals(Files.readAllBytes(directory.resolve("F_3.dat")), packed),
                    "Segundo archivo recuperado");
            check(!Files.exists(directory.resolve(DatasetAccess.MAINTENANCE_NAME)), "Fin de recovery");
            long matches = new BinaryIndexSearch().search(directory, 7, (id, offset, record) -> {
                check(offset == 0, "Nuevo offset");
                check(Arrays.equals(record.payload(), new byte[]{0, -1, 5, 6}), "Bytes del payload");
            });
            check(matches == 2, "Todas las coincidencias recuperadas");
            check(BinaryFileHandler.inspect(directory.resolve("F_1.dat")).deleted() == 0,
                    "Cero borrados");
        }
    }

    private static byte[] fixture() {
        return ByteBuffer.allocate(49).put((byte) 1).putInt(3).putLong(7).putLong(0)
                .put(new byte[]{1, 2, 3}).put((byte) 0).putInt(4).putLong(7).putLong(5)
                .put(new byte[]{0, -1, 5, 6}).array();
    }

    private static void checkNativeLock(Path directory) throws IOException {
        Process probe = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"), "com.hpc.lsdfms.Main",
                "index", directory.toString()).start();
        try {
            String error = new String(probe.getErrorStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            check(probe.waitFor() == 1 && error.contains("ocupado"),
                    "La reentrada no debe liberar el lock del SO");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Prueba de bloqueo interrumpida");
        } finally {
            probe.destroyForcibly();
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
