package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.io.FileCompactor;
import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.CdrPayload;
import com.hpc.lsdfms.model.ColumnIndex;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Servicio de dataset: delta, versiones, contadores, mantenimiento, fallos y concurrencia. */
public final class DatasetServiceTest {
    private static final long WAIT_MS = 60_000;
    private static final CdrPayload PAYLOAD = new CdrPayload("VOZ", 20000, 30, "PE");

    private DatasetServiceTest() { }

    public static void main(String[] args) throws Exception {
        Path root = Path.of(args[0]);
        insertIsFoundInDeltaThenIndex(fixture(root, "insert"));
        deleteAndUpdateAreReflected(fixture(root, "mutate"));
        invalidMutationsFailWithoutVersionChange(fixture(root, "invalid"));
        mutationSubmittedDuringPackWaits(fixture(root, "during-pack"));
        maintenanceFailureSetsNeedsRecover(fixture(root, "failure"));
        openWithMarkerReportsNeedsRecover(fixture(root, "marker"));
        concurrentMixedWorkloadStaysConsistent(fixture(root, "concurrent"));
    }

    private static void insertIsFoundInDeltaThenIndex(Path directory) throws Exception {
        try (DatasetService service = DatasetService.open(directory, 600_000)) {
            DatasetService.Status before = service.status();
            check(!before.stale() && before.dataVersion() == 0 && before.lastMaintenance() == null,
                    "Estado inicial");
            check(before.files() == 3 && before.totalRecords() == 120
                    && before.deletedRecords() == 24 && before.activeRecords() == 96,
                    "Conteos iniciales: " + before);
            DatasetService.MutationResult inserted =
                    service.insert(999_000_111L, 1_800_000_000L, PAYLOAD, WAIT_MS);
            check(inserted.dataVersion() == 1 && "delta".equals(inserted.record().source()),
                    "Resultado del alta");
            DatasetService.Status stale = service.status();
            check(stale.stale() && stale.indexVersion() == 0 && stale.dataVersion() == 1
                    && stale.queueDepth() == 0 && stale.totalRecords() == 121
                    && stale.activeRecords() == 97, "Estado desactualizado: " + stale);
            for (ColumnIndex column : ColumnIndex.values()) {
                long key = switch (column) {
                    case PHONE -> 999_000_111L;
                    case TIMESTAMP -> 1_800_000_000L;
                    case SIZE -> 64;
                };
                var found = service.search(column, key, key, 500);
                boolean present = found.results().stream().anyMatch(r -> r.phone() == 999_000_111L
                        && "delta".equals(r.source()) && r.service().equals("VOZ")
                        && r.cell() == 20000 && r.roaming().equals("PE"));
                check(present, "El delta debe aparecer en " + column);
            }
            check(service.search(ColumnIndex.PHONE, 999_000_111L, 999_000_111L, 5).indexVersion() == 0,
                    "indexVersion en la búsqueda");
            service.requestMaintenance();
            check(service.awaitMaintenanceIdle(WAIT_MS), "El mantenimiento debe terminar");
            DatasetService.Status fresh = service.status();
            check(!fresh.stale() && fresh.indexVersion() == fresh.dataVersion()
                    && fresh.deletedRecords() == 0 && fresh.totalRecords() == 97
                    && fresh.activeRecords() == 97 && !fresh.needsRecover()
                    && !fresh.maintenanceRunning(), "Estado tras mantenimiento: " + fresh);
            check(fresh.lastMaintenance() != null && fresh.lastMaintenance().removedRecords() == 24
                    && fresh.lastMaintenance().indexes().equals(List.of("phone", "timestamp", "size")),
                    "lastMaintenance");
            var after = service.search(ColumnIndex.PHONE, 999_000_111L, 999_000_111L, 5);
            check(after.results().size() == 1 && "index".equals(after.results().get(0).source())
                    && after.indexVersion() == 1, "Tras reindexar: fuente index");
            checkPhysical(directory, 97, 0);
        }
    }

    private static void deleteAndUpdateAreReflected(Path directory) throws Exception {
        try (DatasetService service = DatasetService.open(directory, 600_000)) {
            var all = service.search(ColumnIndex.PHONE, 0, Long.MAX_VALUE, 500).results();
            check(all.size() == 96, "Activos iniciales: " + all.size());
            var victim = all.get(3);
            var moved = all.get(10);
            var created = service.insert(777_000_001L, 1_700_000_500L, PAYLOAD, WAIT_MS).record();
            long deleteVersion = service.deleteChecked(victim.fileId(), victim.offset(),
                    victim.phone(), WAIT_MS);
            check(deleteVersion == 2, "Versión tras baja: " + deleteVersion);
            var updated = service.update(moved.fileId(), moved.offset(), 888_000_002L,
                    1_700_000_900L, new CdrPayload("SMS", 55555, 7, "CL"), WAIT_MS);
            check(updated.dataVersion() == 3 && updated.previous().offset() == moved.offset()
                    && updated.record().payloadLen() == moved.payloadLen(), "Actualización");
            long deletedDelta = service.delete(created.fileId(), created.offset(), WAIT_MS);
            check(deletedDelta == 4, "Baja de un registro del delta");
            assertCounts(service, 122, 27, 95);
            checkSearchState(service, victim, moved, created);
            service.requestMaintenance();
            check(service.awaitMaintenanceIdle(WAIT_MS), "Mantenimiento");
            assertCounts(service, 95, 0, 95);
            checkSearchState(service, victim, moved, created);
            var final888 = service.search(ColumnIndex.PHONE, 888_000_002L, 888_000_002L, 5).results();
            check(final888.size() == 1 && "index".equals(final888.get(0).source())
                    && final888.get(0).service().equals("SMS") && final888.get(0).cell() == 55555,
                    "El actualizado queda indexado con sus campos");
            checkPhysical(directory, 95, 0);
        }
    }

    private static void checkSearchState(DatasetService service, DatasetService.RecordView victim,
            DatasetService.RecordView moved, DatasetService.RecordView created) throws IOException {
        check(service.search(ColumnIndex.PHONE, victim.phone(), victim.phone(), 50).results()
                .stream().noneMatch(r -> r.fileId() == victim.fileId() && r.offset() == victim.offset()),
                "El borrado no aparece");
        check(service.search(ColumnIndex.PHONE, moved.phone(), moved.phone(), 50).results()
                .stream().noneMatch(r -> r.fileId() == moved.fileId() && r.offset() == moved.offset()),
                "El original actualizado no aparece");
        check(service.search(ColumnIndex.PHONE, 888_000_002L, 888_000_002L, 5).results().size() == 1,
                "El nuevo valor aparece");
        check(service.search(ColumnIndex.PHONE, 777_000_001L, 777_000_001L, 5).results().isEmpty(),
                "El alta borrada no aparece (delta excluye borrados)");
        var limited = service.search(ColumnIndex.PHONE, 0, Long.MAX_VALUE, 10);
        check(limited.results().size() == 10 && limited.truncated(), "Límite y truncado");
    }

    private static void invalidMutationsFailWithoutVersionChange(Path directory) throws Exception {
        DatasetService service = DatasetService.open(directory, 600_000);
        try {
            var any = service.search(ColumnIndex.PHONE, 0, Long.MAX_VALUE, 1).results().get(0);
            service.delete(any.fileId(), any.offset(), WAIT_MS);
            expectFailure(IllegalStateException.class, () -> service.delete(any.fileId(), any.offset(), WAIT_MS));
            expectFailure(IllegalArgumentException.class, () -> service.delete(99, 0, WAIT_MS));
            expectFailure(IllegalArgumentException.class,
                    () -> service.insert(-5, 10, PAYLOAD, WAIT_MS));
            expectFailure(IllegalStateException.class, () -> service.deleteChecked(1, 0, 123_456_789L, WAIT_MS));
            check(service.status().dataVersion() == 1, "Los fallos no suben la versión");
            assertCounts(service, 120, 25, 95);
            service.close();
            boolean rejected = false;
            try {
                service.delete(1, 0, WAIT_MS);
            } catch (IllegalStateException e) {
                rejected = true;
            }
            check(rejected, "Un servicio cerrado rechaza mutaciones");
        } finally {
            service.close();
        }
    }

    private static void mutationSubmittedDuringPackWaits(Path directory) throws Exception {
        CountDownLatch inPack = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean blocking = new AtomicBoolean(true);
        SequentialEngine.Operations operations = new SequentialEngine.Operations() {
            @Override
            public void compact(Path source, Path temporary) throws IOException {
                if (blocking.get()) {
                    inPack.countDown();
                    try {
                        check(release.await(WAIT_MS, TimeUnit.MILLISECONDS), "liberación");
                    } catch (InterruptedException e) {
                        throw new IOException("interrumpido", e);
                    }
                }
                FileCompactor.compact(source, temporary);
            }
        };
        try (DatasetService service = DatasetService.open(directory, 600_000, operations)) {
            service.insert(555_000_001L, 1_700_000_001L, PAYLOAD, WAIT_MS);
            service.requestMaintenance();
            check(inPack.await(WAIT_MS, TimeUnit.MILLISECONDS), "PACK iniciado");
            check(service.status().maintenanceRunning(), "maintenanceRunning durante PACK");
            Object[] outcome = new Object[1];
            Thread submitter = new Thread(() -> {
                try {
                    outcome[0] = service.insert(555_000_002L, 1_700_000_002L, PAYLOAD, WAIT_MS);
                } catch (Throwable t) {
                    outcome[0] = t;
                }
            }, "test-submitter");
            submitter.start();
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (service.queueDepth() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(5);
            }
            Thread.sleep(300);
            check(service.queueDepth() == 1 && submitter.isAlive() && outcome[0] == null,
                    "La mutación espera durante PACK");
            check(service.status().dataVersion() == 1, "Sin aplicar aún");
            release.countDown();
            submitter.join(WAIT_MS);
            check(outcome[0] instanceof DatasetService.MutationResult, "Se aplicó: " + outcome[0]);
            service.requestMaintenance();
            check(service.awaitMaintenanceIdle(WAIT_MS), "Mantenimiento posterior");
            DatasetService.Status status = service.status();
            check(status.dataVersion() == 2 && status.indexVersion() == 2 && !status.stale()
                    && status.queueDepth() == 0, "Estado final: " + status);
            check(service.search(ColumnIndex.PHONE, 555_000_001L, 555_000_002L, 10).results().size() == 2,
                    "Ambos registros presentes");
        }
    }

    private static void maintenanceFailureSetsNeedsRecover(Path directory) throws Exception {
        AtomicBoolean fail = new AtomicBoolean(true);
        SequentialEngine.Operations operations = new SequentialEngine.Operations() {
            @Override
            public void compact(Path source, Path temporary) throws IOException {
                if (fail.get()) {
                    throw new IOException("Fallo inyectado en compact");
                }
                FileCompactor.compact(source, temporary);
            }
        };
        try (DatasetService service = DatasetService.open(directory, 0, operations)) {
            service.insert(444_000_001L, 1_700_000_003L, PAYLOAD, WAIT_MS);
            check(service.awaitMaintenanceIdle(WAIT_MS), "El ciclo fallido termina sin colgarse");
            DatasetService.Status failed = service.status();
            check(failed.needsRecover() && failed.stale() && failed.indexVersion() == 0
                    && !failed.maintenanceRunning() && failed.lastMaintenance() == null,
                    "needsRecover tras fallo: " + failed);
            check(service.lastMaintenanceError().contains("Fallo inyectado"), "Error registrado");
            expectFailure(java.io.IOException.class, () -> service.insert(444_000_002L, 1, PAYLOAD, WAIT_MS));
            fail.set(false);
            service.requestMaintenance();
            check(service.awaitMaintenanceIdle(WAIT_MS), "Recuperación");
            DatasetService.Status healed = service.status();
            check(!healed.needsRecover() && !healed.stale() && healed.lastMaintenance() != null
                    && service.lastMaintenanceError() == null, "Recuperado: " + healed);
            check(service.search(ColumnIndex.PHONE, 444_000_001L, 444_000_001L, 5).results().size() == 1,
                    "El registro sobrevive a la recuperación");
            service.insert(444_000_003L, 1_700_000_004L, PAYLOAD, WAIT_MS);
        }
    }

    private static void openWithMarkerReportsNeedsRecover(Path directory) throws Exception {
        Files.writeString(directory.resolve(".lsdfms-maintenance"), "LSDFMS PACK v1\n");
        try (DatasetService service = DatasetService.open(directory, 0)) {
            check(service.status().needsRecover() && !service.status().stale(), "Marcador al abrir");
            service.requestMaintenance();
            check(service.awaitMaintenanceIdle(WAIT_MS), "Recuperación al abrir");
            check(!service.status().needsRecover()
                    && service.search(ColumnIndex.PHONE, 0, Long.MAX_VALUE, 500).results().size() == 96,
                    "Recuperado con índices construidos");
        }
    }

    private static void concurrentMixedWorkloadStaysConsistent(Path directory) throws Exception {
        int threads = 8;
        int operations = 60;
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<Map<Long, Boolean>> live = new ArrayList<>();
        try (DatasetService service = DatasetService.open(directory, 0)) {
            long initialActive = service.status().activeRecords();
            Thread[] workers = new Thread[threads];
            for (int t = 0; t < threads; t++) {
                Map<Long, Boolean> mine = new HashMap<>();
                live.add(mine);
                long base = 10_000_000_000L * (t + 1);
                Random random = new Random(t);
                workers[t] = new Thread(() -> {
                    try {
                        long next = base;
                        for (int i = 0; i < operations; i++) {
                            int action = mine.isEmpty() ? 0 : random.nextInt(3);
                            if (action == 0) {
                                long phone = next++;
                                service.insert(phone, 1_700_100_000L + i, PAYLOAD, WAIT_MS);
                                mine.put(phone, true);
                            } else {
                                long phone = mine.keySet().iterator().next();
                                long replacement = action == 2 ? next++ : -1;
                                mutateExisting(service, phone, replacement);
                                mine.remove(phone);
                                if (replacement > 0) {
                                    mine.put(replacement, true);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }, "test-worker-" + t);
            }
            boolean[] stop = {false};
            Thread[] searchers = new Thread[2];
            for (int s = 0; s < searchers.length; s++) {
                Random random = new Random(100 + s);
                searchers[s] = new Thread(() -> {
                    try {
                        while (!stopped(stop)) {
                            ColumnIndex column = ColumnIndex.values()[random.nextInt(3)];
                            service.search(column, 0, Long.MAX_VALUE, 1 + random.nextInt(50));
                            service.status();
                        }
                    } catch (Throwable e) {
                        errors.add(e);
                    }
                }, "test-searcher-" + s);
                searchers[s].start();
            }
            for (Thread worker : workers) {
                worker.start();
            }
            for (Thread worker : workers) {
                worker.join(120_000);
                check(!worker.isAlive(), "Sin bloqueo mutuo: " + worker.getName());
            }
            synchronized (stop) {
                stop[0] = true;
            }
            for (Thread searcher : searchers) {
                searcher.join(60_000);
                check(!searcher.isAlive(), "Buscador sin bloqueo");
            }
            check(errors.isEmpty(), "Sin excepciones: " + errors);
            check(service.awaitMaintenanceIdle(WAIT_MS), "Mantenimiento ocioso");
            service.requestMaintenance();
            check(service.awaitMaintenanceIdle(WAIT_MS), "Mantenimiento final");
            long expected = initialActive;
            for (Map<Long, Boolean> mine : live) {
                expected += mine.size();
            }
            DatasetService.Status status = service.status();
            check(status.activeRecords() == expected && status.deletedRecords() == 0
                    && status.totalRecords() == expected && !status.stale() && !status.needsRecover()
                    && status.queueDepth() == 0, "Conteos finales: " + status + " esperado " + expected);
            checkPhysical(directory, expected, 0);
            for (Map<Long, Boolean> mine : live) {
                for (long phone : mine.keySet()) {
                    check(service.search(ColumnIndex.PHONE, phone, phone, 5).results().size() == 1,
                            "Presente: " + phone);
                }
            }
        }
    }

    /** Busca el registro por teléfono y lo borra o reemplaza; reintenta si un PACK movió offsets. */
    private static void mutateExisting(DatasetService service, long phone, long replacement)
            throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            var hits = service.search(ColumnIndex.PHONE, phone, phone, 5).results();
            check(hits.size() == 1, "Debe existir exactamente uno para " + phone + ": " + hits.size());
            var hit = hits.get(0);
            try {
                if (replacement > 0) {
                    service.updateChecked(hit.fileId(), hit.offset(), phone, replacement,
                            1_700_200_000L, PAYLOAD, WAIT_MS);
                } else {
                    service.deleteChecked(hit.fileId(), hit.offset(), phone, WAIT_MS);
                }
                return;
            } catch (com.hpc.lsdfms.core.ResultSlot.FailedException e) {
                if (!(e.getCause() instanceof IllegalStateException
                        || e.getCause() instanceof IllegalArgumentException)) {
                    throw e;
                }
            }
        }
        throw new AssertionError("Sin éxito tras reintentos para " + phone);
    }

    private static boolean stopped(boolean[] stop) {
        synchronized (stop) {
            return stop[0];
        }
    }

    private static void assertCounts(DatasetService service, long total, long deleted, long active) {
        DatasetService.Status status = service.status();
        check(status.totalRecords() == total && status.deletedRecords() == deleted
                && status.activeRecords() == active,
                "Conteos esperados " + total + "/" + deleted + "/" + active + " pero " + status);
    }

    /** Los contadores deben coincidir con un escaneo físico de las cabeceras. */
    private static void checkPhysical(Path directory, long active, long deleted) throws IOException {
        long physicalActive = 0;
        long physicalDeleted = 0;
        for (var source : DatasetFiles.list(directory)) {
            var stats = BinaryFileHandler.inspect(source.path());
            physicalActive += stats.active();
            physicalDeleted += stats.deleted();
        }
        check(physicalActive == active && physicalDeleted == deleted,
                "Escaneo físico " + physicalActive + "/" + physicalDeleted);
    }

    private interface Call {
        void run() throws Exception;
    }

    private static void expectFailure(Class<? extends Throwable> cause, Call call) throws Exception {
        try {
            call.run();
        } catch (com.hpc.lsdfms.core.ResultSlot.FailedException e) {
            check(cause.isInstance(e.getCause()), "Causa esperada " + cause + " pero " + e.getCause());
            return;
        } catch (RuntimeException e) {
            check(cause.isInstance(e), "Excepción esperada " + cause + " pero " + e);
            return;
        }
        throw new AssertionError("Debía fallar con " + cause);
    }

    /** 3 archivos x 40 registros (8 borrados cada uno), teléfonos y timestamps variados. */
    private static Path fixture(Path root, String name) throws IOException {
        Path directory = Files.createDirectory(root.resolve(name));
        Random random = new Random(5);
        String[] services = {"VOZ", "SMS", "DATA"};
        for (int id = 1; id <= 3; id++) {
            ByteBuffer data = ByteBuffer.allocate(40 * (CdrFormat.HEADER_BYTES + 200))
                    .order(CdrFormat.BYTE_ORDER);
            for (int i = 0; i < 40; i++) {
                byte[] payload = new CdrPayload(services[random.nextInt(3)], 10000 + random.nextInt(9000),
                        random.nextInt(3600), "CO").build(64 + random.nextInt(100));
                data.put((byte) (i % 5 == 4 ? 1 : 0)).putInt(payload.length)
                        .putLong(1_000L + id * 100 + i).putLong(1_700_000_000L + random.nextInt(1000));
                data.put(payload);
            }
            Files.write(directory.resolve("F_" + id + ".dat"),
                    java.util.Arrays.copyOf(data.array(), data.position()));
        }
        return directory;
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
