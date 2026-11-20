package com.hpc.lsdfms.io;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Random;

/** Prueba transferencias parciales, cero progreso, truncamiento e interrupción. */
public final class FileTransfersTest {
    private FileTransfersTest() { }

    public static void main(String[] args) throws Exception {
        Path source = Path.of(args[0]).resolve("source.bin");
        byte[] bytes = new byte[200000];
        new Random(42).nextBytes(bytes);
        Files.write(source, bytes);
        for (String mode : new String[]{"partial", "zero", "mixed"}) {
            Path target = Path.of(args[0]).resolve(mode + ".bin");
            try (FileChannel input = FileChannel.open(source, StandardOpenOption.READ);
                    FileChannel output = FileChannel.open(target,
                            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                int[] calls = {0};
                FileTransfers.copy(input, output, 17, bytes.length - 34,
                        (from, position, count, to) -> {
                            calls[0]++;
                            if ("zero".equals(mode) || ("mixed".equals(mode) && calls[0] % 3 == 0)) {
                                return 0;
                            }
                            return from.transferTo(position, Math.min(count, 4096), to);
                        });
                check(calls[0] > 1, "Deben ser necesarias varias iteraciones");
            }
            check(Arrays.equals(Files.readAllBytes(target),
                    Arrays.copyOfRange(bytes, 17, bytes.length - 17)), "Bytes exactos: " + mode);
        }
        Path target = Path.of(args[0]).resolve("failure.bin");
        try (FileChannel input = FileChannel.open(source,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
                FileChannel output = FileChannel.open(target,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            Thread.currentThread().interrupt();
            try {
                FileTransfers.copy(input, output, 0, bytes.length);
                throw new AssertionError("Debe responder a interrupción");
            } catch (InterruptedIOException expected) {
                check(Thread.currentThread().isInterrupted(), "Conserva estado de interrupción");
            } finally {
                Thread.interrupted();
            }
            try {
                FileTransfers.copy(input, output, -1, 1);
                throw new AssertionError("Debe rechazar rango inválido");
            } catch (IOException expected) {
                check(output.size() == 0, "Sin escritura para rango inválido");
            }
            try {
                FileTransfers.copy(input, output, 0, bytes.length, (from, position, count, to) -> {
                    from.truncate(0);
                    return 0;
                });
                throw new AssertionError("No debe entrar en bucle al encontrar EOF");
            } catch (IOException expected) {
                check(output.size() == 0, "Copia incompleta rechazada");
            }
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
