package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.model.ColumnIndex;
import com.hpc.lsdfms.search.ColumnSearch;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Índices por columna: un hilo por índice, orden, fallos atómicos y búsqueda de rango. */
public final class ColumnIndexTest {
    private record Rec(boolean deleted, int fileId, long offset, long phone, long timestamp, int length) { }

    private ColumnIndexTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        concurrentThreadsAndContent(fixture(root, "threads"));
        failurePropagatesAndKeepsPreviousSet(fixture(root, "failure"), fixture(root, "failure-fresh"));
        publishFailureInvalidatesSet(fixture(root, "publish"));
        externalPathMatchesMemoryPath(fixture(root, "external"));
        searchSemantics(fixture(root, "search"));
        heldAccessEntryPoints(fixture(root, "held"));
    }

    private static void concurrentThreadsAndContent(Path directory) throws Exception {
        Set<String> names = ConcurrentHashMap.newKeySet();
        CountDownLatch together = new CountDownLatch(3);
        long count = new ColumnIndexBuilder(new ParallelEngine.Scanner() { },
                new SequentialEngine.Operations() { }, column -> {
                    names.add(Thread.currentThread().getName());
                    together.countDown();
                    try {
                        check(together.await(10, TimeUnit.SECONDS), "Los tres hilos deben coincidir en el tiempo");
                    } catch (InterruptedException e) {
                        throw new IOException("interrumpido", e);
                    }
                }).build(directory);
        check(names.equals(Set.of("lsdfms-index-phone", "lsdfms-index-timestamp", "lsdfms-index-size")),
                "Nombres de hilo: " + names);
        check(count == records(directory).stream().filter(r -> !r.deleted()).count(), "Conteo activo");
        for (ColumnIndex column : ColumnIndex.values()) {
            check(Arrays.equals(Files.readAllBytes(directory.resolve(column.fileName())),
                    expectedBytes(directory, column)), "Contenido ordenado: " + column);
        }
        clean(directory);
    }

    private static void failurePropagatesAndKeepsPreviousSet(Path directory, Path fresh) throws Exception {
        new ColumnIndexBuilder().build(directory);
        byte[][] before = new byte[3][];
        for (ColumnIndex column : ColumnIndex.values()) {
            before[column.ordinal()] = Files.readAllBytes(directory.resolve(column.fileName()));
        }
        for (Path target : List.of(directory, fresh)) {
            CountDownLatch together = new CountDownLatch(3);
            boolean[] cancelled = {false};
            try {
                new ColumnIndexBuilder(new ParallelEngine.Scanner() { },
                        new SequentialEngine.Operations() { }, column -> {
                            together.countDown();
                            try {
                                together.await(10, TimeUnit.SECONDS);
                                if (column == ColumnIndex.SIZE) {
                                    throw new IOException("Fallo inyectado en size");
                                }
                                new CountDownLatch(1).await();
                            } catch (InterruptedException e) {
                                cancelled[0] = true;
                                Thread.currentThread().interrupt();
                                throw new java.io.InterruptedIOException("cancelado");
                            }
                        }).build(target);
                throw new AssertionError("Debe propagar el fallo");
            } catch (IOException expected) {
                check(expected.getMessage().contains("inyectado"), "Conserva la causa original");
            }
            check(cancelled[0], "Cancela y espera a los demás hilos");
            check(Thread.getAllStackTraces().keySet().stream().noneMatch(
                    t -> t.isAlive() && t.getName().startsWith("lsdfms-index-")), "Sin hilos vivos");
            noTemporaries(target);
            for (ColumnIndex column : ColumnIndex.values()) {
                Path file = target.resolve(column.fileName());
                if (target == directory) {
                    check(Arrays.equals(Files.readAllBytes(file), before[column.ordinal()]),
                            "Conjunto anterior intacto: " + column);
                } else {
                    check(!Files.exists(file), "Sin índices parciales: " + column);
                }
            }
            try (var access = DatasetAccess.acquire(target)) {
                check(access.directory() != null, "El bloqueo se libera tras el fallo");
            }
        }
    }

    private static void publishFailureInvalidatesSet(Path directory) throws Exception {
        new ColumnIndexBuilder().build(directory);
        try {
            new ColumnIndexBuilder(new ParallelEngine.Scanner() { }, new SequentialEngine.Operations() {
                @Override
                public void replace(Path temporary, Path target) throws IOException {
                    if (target.getFileName().toString().equals("index_size.idx")) {
                        throw new IOException("Fallo al publicar size");
                    }
                    SequentialEngine.Operations.super.replace(temporary, target);
                }
            }).build(directory);
            throw new AssertionError("Debe fallar la publicación");
        } catch (IOException expected) {
            check(expected.getMessage().contains("publicar"), "Causa de publicación");
        }
        for (ColumnIndex column : ColumnIndex.values()) {
            check(!Files.exists(directory.resolve(column.fileName())),
                    "Un conjunto a medias se invalida: " + column);
        }
        noTemporaries(directory);
    }

    private static void externalPathMatchesMemoryPath(Path directory) throws Exception {
        System.setProperty("lsdfms.index.mode", "external");
        System.setProperty("lsdfms.index.chunkEntries", "17");
        System.setProperty("lsdfms.index.fanIn", "3");
        try {
            new ColumnIndexBuilder().build(directory);
        } finally {
            System.clearProperty("lsdfms.index.mode");
            System.clearProperty("lsdfms.index.chunkEntries");
            System.clearProperty("lsdfms.index.fanIn");
        }
        for (ColumnIndex column : ColumnIndex.values()) {
            check(Arrays.equals(Files.readAllBytes(directory.resolve(column.fileName())),
                    expectedBytes(directory, column)), "Ruta externa idéntica: " + column);
        }
        noTemporaries(directory);
    }

    private static void searchSemantics(Path directory) throws Exception {
        new ColumnIndexBuilder().build(directory);
        List<Rec> all = records(directory);
        // Borra lógicamente algunos registros activos después de indexar.
        Random random = new Random(3);
        for (Rec rec : all) {
            if (!rec.deleted() && random.nextInt(5) == 0) {
                setDeleted(directory, rec);
            }
        }
        List<Rec> live = records(directory).stream().filter(r -> !r.deleted()).toList();
        for (ColumnIndex column : ColumnIndex.values()) {
            List<Long> keys = live.stream().map(r -> key(r, column)).sorted().toList();
            long[][] ranges = {{keys.get(0), keys.get(keys.size() - 1)},
                    {keys.get(10), keys.get(10)}, {keys.get(20), keys.get(60)},
                    {Long.MIN_VALUE, Long.MAX_VALUE}, {-5, -1}};
            for (long[] range : ranges) {
                List<ColumnSearch.Hit> hits = new ArrayList<>();
                var result = new ColumnSearch().search(directory, column, range[0], range[1],
                        ColumnSearch.NO_LIMIT, hits::add);
                List<Rec> expected = live.stream()
                        .filter(r -> key(r, column) >= range[0] && key(r, column) <= range[1])
                        .sorted(comparator(column)).toList();
                check(hits.size() == expected.size() && result.matches() == expected.size()
                        && !result.truncated(), "Cantidad " + column + Arrays.toString(range));
                for (int i = 0; i < hits.size(); i++) {
                    var hit = hits.get(i);
                    var rec = expected.get(i);
                    check(hit.fileId() == rec.fileId() && hit.offset() == rec.offset()
                            && hit.phone() == rec.phone() && hit.timestamp() == rec.timestamp()
                            && hit.payloadLen() == rec.length(), "Hit " + column + " #" + i);
                }
            }
            List<ColumnSearch.Hit> limited = new ArrayList<>();
            var result = new ColumnSearch().search(directory, column, keys.get(0),
                    keys.get(keys.size() - 1), 7, limited::add);
            check(limited.size() == 7 && result.truncated(), "Límite y truncado");
            var exactAll = new ColumnSearch().search(directory, column, keys.get(0),
                    keys.get(keys.size() - 1), keys.size(), hit -> { });
            check(exactAll.matches() == keys.size() && !exactAll.truncated(), "Límite exacto no trunca");
        }
        try {
            new ColumnSearch().search(directory, ColumnIndex.SIZE, 5, 4, 1, hit -> { });
            throw new AssertionError("lo > hi debe rechazarse");
        } catch (IllegalArgumentException expected) {
            check(expected.getMessage().contains("rango"), "Mensaje de rango");
        }
        // Índice obsoleto: la clave del registro activo ya no coincide con la del índice.
        Rec victim = live.get(0);
        Path file = directory.resolve("F_" + victim.fileId() + ".dat");
        byte[] data = Files.readAllBytes(file);
        ByteBuffer.wrap(data).putLong((int) victim.offset() + 13, victim.timestamp() + 1);
        Files.write(file, data);
        try {
            new ColumnSearch().search(directory, ColumnIndex.TIMESTAMP, Long.MIN_VALUE, Long.MAX_VALUE,
                    ColumnSearch.NO_LIMIT, hit -> { });
            throw new AssertionError("Debe detectar referencia obsoleta");
        } catch (IOException expected) {
            check(expected.getMessage().contains("obsoleta"), "Referencia obsoleta");
        }
    }

    private static void heldAccessEntryPoints(Path directory) throws Exception {
        try (DatasetAccess access = DatasetAccess.acquire(directory)) {
            long count = new ColumnIndexBuilder().buildLocked(access);
            long[] found = {0};
            new ColumnSearch().searchLocked(access, ColumnIndex.SIZE, Long.MIN_VALUE, Long.MAX_VALUE,
                    ColumnSearch.NO_LIMIT, hit -> found[0]++);
            check(found[0] == count, "Entradas con acceso ya adquirido");
            try {
                new ColumnIndexBuilder().build(directory);
                throw new AssertionError("El acceso debe seguir ocupado");
            } catch (IOException expected) {
                check(expected.getMessage().contains("ocupado"), "Exclusión");
            }
        }
    }

    // --- Oráculo de fuerza bruta -------------------------------------------------------------

    private static long key(Rec rec, ColumnIndex column) {
        return switch (column) {
            case PHONE -> rec.phone();
            case TIMESTAMP -> rec.timestamp();
            case SIZE -> rec.length();
        };
    }

    private static Comparator<Rec> comparator(ColumnIndex column) {
        return Comparator.comparingLong((Rec r) -> key(r, column))
                .thenComparingInt(Rec::fileId).thenComparingLong(Rec::offset);
    }

    private static byte[] expectedBytes(Path directory, ColumnIndex column) throws IOException {
        List<Rec> active = records(directory).stream().filter(r -> !r.deleted())
                .sorted(comparator(column)).toList();
        ByteBuffer out = ByteBuffer.allocate(active.size() * 20);
        for (Rec rec : active) {
            out.putLong(key(rec, column)).putInt(rec.fileId()).putLong(rec.offset());
        }
        return out.array();
    }

    private static List<Rec> records(Path directory) throws IOException {
        List<Rec> result = new ArrayList<>();
        for (int id : new int[]{1, 2, 5}) {
            ByteBuffer data = ByteBuffer.wrap(Files.readAllBytes(directory.resolve("F_" + id + ".dat")));
            while (data.hasRemaining()) {
                int offset = data.position();
                boolean deleted = data.get() != 0;
                int length = data.getInt();
                long phone = data.getLong();
                long timestamp = data.getLong();
                data.position(data.position() + length);
                result.add(new Rec(deleted, id, offset, phone, timestamp, length));
            }
        }
        return result;
    }

    private static void setDeleted(Path directory, Rec rec) throws IOException {
        Path file = directory.resolve("F_" + rec.fileId() + ".dat");
        byte[] data = Files.readAllBytes(file);
        data[(int) rec.offset()] = 1;
        Files.write(file, data);
    }

    private static Path fixture(Path root, String name) throws IOException {
        Path directory = Files.createDirectory(root.resolve(name));
        Random random = new Random(11);
        for (int id : new int[]{1, 2, 5}) {
            ByteBuffer data = ByteBuffer.allocate(200 * (21 + 40));
            for (int i = 0; i < 200; i++) {
                int length = random.nextInt(41);
                data.put((byte) (random.nextInt(5) == 0 ? 1 : 0)).putInt(length)
                        .putLong(random.nextInt(40)).putLong(1_700_000_000L + random.nextInt(60));
                data.put(new byte[length]);
            }
            Files.write(directory.resolve("F_" + id + ".dat"), Arrays.copyOf(data.array(), data.position()));
        }
        return directory;
    }

    private static void clean(Path directory) throws IOException {
        noTemporaries(directory);
    }

    private static void noTemporaries(Path directory) throws IOException {
        try (var paths = Files.list(directory)) {
            check(paths.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")
                    || p.getFileName().toString().startsWith(".lsdfms-index-")), "Sin temporales");
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
