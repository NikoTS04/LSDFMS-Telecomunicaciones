package com.hpc.lsdfms.io;

import com.hpc.lsdfms.core.SequentialEngine;
import com.hpc.lsdfms.model.CdrPayload;
import com.hpc.lsdfms.search.BinaryIndexSearch;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Prueba alta, baja lógica y actualización. Uso: {@code <directorio temporal> <dataset generado>}.
 * El segundo argumento (salida de generate_cdr.py) se usa solo para leer payloads reales.
 */
public final class RecordWriterTest {
    private RecordWriterTest() { }

    public static void main(String[] args) throws Exception {
        payloadRoundtrip(Path.of(args[1]));
        Path dir = Files.createDirectory(Path.of(args[0]).resolve("mutations"));
        byte[] seed = new CdrPayload("VOZ", 10001, 5, "PE").build(80);
        Files.write(dir.resolve("F_1.dat"), record(0, 7, seed));
        Files.write(dir.resolve("F_2.dat"), record(0, 8, seed));
        Files.write(dir.resolve("manifest.json"), "{\"stale\":true}".getBytes(StandardCharsets.UTF_8));
        new SequentialEngine().index(dir);

        RecordWriter.Location appended;
        RecordWriter.Location updated;
        try (DatasetAccess access = DatasetAccess.acquire(dir)) {
            RecordWriter writer = new RecordWriter(access);
            appended = writer.append(9001, 1700000000L, new CdrPayload("SMS", 20000, 1, "CL").build());
            check(appended.fileId() == 2 && appended.offset() == 21 + seed.length, "Alta en el último archivo");
            rejects(() -> writer.append(1, 1, new byte[63]), IllegalArgumentException.class, "payload corto");
            rejects(() -> writer.append(1, 1, new byte[1048577]), IllegalArgumentException.class, "payload largo");
            check(writer.append(9002, 1, new byte[1048576]).fileId() == 2, "Payload máximo admitido");

            writer.markDeleted(1, 0, 7);
            rejects(() -> writer.markDeleted(1, 0, 7), IllegalStateException.class, "ya borrado");
            rejects(() -> writer.markDeleted(2, 0, 999), IllegalStateException.class, "teléfono distinto");
            rejects(() -> writer.markDeleted(2, 100000000L, 8), IllegalArgumentException.class, "offset fuera");
            rejects(() -> writer.markDeleted(2, -1, 8), IllegalArgumentException.class, "offset negativo");
            rejects(() -> writer.markDeleted(2, 5, 8), IllegalStateException.class, "offset desalineado");
            rejects(() -> writer.markDeleted(9, 0, 8), IllegalArgumentException.class, "archivo inexistente");
            check(flag(dir.resolve("F_2.dat"), 0) == 0, "Los rechazos no alteran el registro");

            updated = writer.update(2, 0, 8, 8080, 1700000001L, new CdrPayload("DATA", 30000, 9, "US").build());
            check(updated.offset() > appended.offset(), "La actualización agrega al final");
            rejects(() -> writer.update(2, 0, 8, 1, 1, seed), IllegalStateException.class, "actualizar borrado");
            long before = Files.size(dir.resolve("F_2.dat"));
            rejects(() -> writer.update(2, appended.offset(), 1, 1, 1, seed), IllegalStateException.class,
                    "actualizar con teléfono incorrecto");
            check(Files.size(dir.resolve("F_2.dat")) == before, "Una referencia inválida no agrega nada");
        }
        check(flag(dir.resolve("F_1.dat"), 0) == 1, "Baja lógica de F_1");
        check(flag(dir.resolve("F_2.dat"), 0) == 1, "Original actualizado queda borrado");
        check(flag(dir.resolve("F_2.dat"), appended.offset()) == 0, "Alta activa");
        check(flag(dir.resolve("F_2.dat"), updated.offset()) == 0, "Nuevo registro activo");

        // Visible para inspect/scan y detectado como obsoleto por el índice previo.
        List<Long> phones = new ArrayList<>();
        BinaryFileHandler.scan(dir.resolve("F_2.dat"), (phone, offset) -> phones.add(phone));
        check(phones.equals(List.of(9001L, 9002L, 8080L)), "scan lista solo activos: " + phones);
        BinaryFileHandler.CdrRecord read = BinaryFileHandler.readActive(dir.resolve("F_2.dat"),
                updated.offset(), 8080);
        check(CdrPayload.parse(read.payload()).equals(new CdrPayload("DATA", 30000, 9, "US")), "Payload leído");

        // PACK elimina los borrados; el índice y la búsqueda siguen funcionando sin manifest.
        new SequentialEngine().pack(dir);
        check(BinaryFileHandler.inspect(dir.resolve("F_1.dat")).records() == 0, "F_1 compactado");
        check(BinaryFileHandler.inspect(dir.resolve("F_2.dat")).deleted() == 0, "Sin borrados tras PACK");
        for (long phone : new long[]{9001, 9002, 8080}) {
            check(search(dir, phone) == 1, "Búsqueda tras PACK: " + phone);
        }
        check(search(dir, 7) == 0 && search(dir, 8) == 0, "Registros borrados ausentes");
    }

    private static void payloadRoundtrip(Path generated) throws IOException {
        CdrPayload fields = new CdrPayload("DATA", 99999, 3600, "EC");
        for (int length : new int[]{64, 65, 500, 1048576}) {
            byte[] bytes = fields.build(length);
            check(bytes.length == length, "Longitud exacta " + length);
            check(CdrPayload.parse(bytes).equals(fields), "Roundtrip " + length);
        }
        check(new CdrPayload("SMS", 10000, 0, "PE").build().length == 64, "Tamaño mínimo por defecto");
        rejects(() -> new CdrPayload("FAX", 10000, 0, "PE"), IllegalArgumentException.class, "service");
        rejects(() -> new CdrPayload("SMS", 9999, 0, "PE"), IllegalArgumentException.class, "cell");
        rejects(() -> new CdrPayload("SMS", 10000, 3601, "PE"), IllegalArgumentException.class, "duration");
        rejects(() -> new CdrPayload("SMS", 10000, 0, "AR"), IllegalArgumentException.class, "roaming");
        rejects(() -> new CdrPayload("SMS", 10000, 0, "PE").build(63), IllegalArgumentException.class, "tamaño");
        rejects(() -> CdrPayload.parse("hola".getBytes(StandardCharsets.US_ASCII)),
                IllegalArgumentException.class, "payload sin campos");
        // Payloads producidos por scripts/generate_cdr.py.
        long[] count = {0};
        BinaryFileHandler.scan(generated.resolve("F_1.dat"), (phone, offset) -> {
            CdrPayload parsed = CdrPayload.parse(BinaryFileHandler.readActive(
                    generated.resolve("F_1.dat"), offset, phone).payload());
            check(parsed.cell() >= 10000, "Campos del generador");
            count[0]++;
        });
        check(count[0] > 0, "El generador produjo registros activos");
    }

    private static long search(Path dir, long phone) throws IOException {
        new SequentialEngine().index(dir);
        return new BinaryIndexSearch().search(dir, phone, (id, offset, record) -> { });
    }

    private static byte[] record(int deleted, long phone, byte[] payload) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(21 + payload.length);
        buffer.put((byte) deleted).putInt(payload.length).putLong(phone).putLong(1L).put(payload);
        return buffer.array();
    }

    private static int flag(Path path, long offset) throws IOException {
        try (var channel = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.READ)) {
            java.nio.ByteBuffer one = java.nio.ByteBuffer.allocate(1);
            BinaryFileHandler.readFully(channel, one, offset);
            return one.get(0);
        }
    }

    private interface Action {
        void run() throws Exception;
    }

    private static void rejects(Action action, Class<? extends Exception> type, String what) {
        try {
            action.run();
        } catch (Exception e) {
            check(type.isInstance(e), what + ": excepción inesperada " + e);
            check(e.getMessage() != null && !e.getMessage().isBlank(), what + ": mensaje vacío");
            return;
        }
        throw new AssertionError("Debe rechazar: " + what);
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
