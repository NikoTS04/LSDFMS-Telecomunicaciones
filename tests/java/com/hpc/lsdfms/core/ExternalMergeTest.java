package com.hpc.lsdfms.core;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Rechaza runs truncadas/desordenadas y responde a interrupción en merge. */
public final class ExternalMergeTest {
    private ExternalMergeTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        Path good = root.resolve("good.run");
        Path bad = root.resolve("bad.run");
        Path output = Files.createTempFile(root, "output-", ".run");
        Files.write(good, entry(7, 1, 0));
        for (byte[] content : new byte[][] {
                new byte[]{0}, entry(7, 0, 0), entry(7, 1, -1),
                ByteBuffer.allocate(40).put(entry(8, 1, 0)).put(entry(7, 1, 21)).array()}) {
            Files.write(bad, content);
            try {
                ExternalIndex.merge(List.of(good, bad), output);
                throw new AssertionError("Debe rechazar run inválida");
            } catch (IOException expected) {
                // Otro intento válido demuestra que el estado de merge no queda contaminado.
                ExternalIndex.merge(List.of(good), output);
                if (Files.size(output) != 20) {
                    throw new AssertionError("Salida válida incorrecta");
                }
            }
        }
        Thread.currentThread().interrupt();
        try {
            ExternalIndex.merge(List.of(good), output);
            throw new AssertionError("Debe cancelar merge");
        } catch (IOException expected) {
            if (!Thread.currentThread().isInterrupted()) {
                throw new AssertionError("No debe borrar flag de interrupción");
            }
        } finally {
            Thread.interrupted();
        }
        Files.delete(good);
        Files.delete(bad);
        Files.delete(output);
    }

    private static byte[] entry(long phone, int fileId, long offset) {
        return ByteBuffer.allocate(20).putLong(phone).putInt(fileId).putLong(offset).array();
    }
}
