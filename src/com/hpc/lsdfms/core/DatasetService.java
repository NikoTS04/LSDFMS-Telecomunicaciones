package com.hpc.lsdfms.core;

import com.hpc.lsdfms.io.BinaryFileHandler;
import com.hpc.lsdfms.io.DatasetAccess;
import com.hpc.lsdfms.io.DatasetFiles;
import com.hpc.lsdfms.io.RecordWriter;
import com.hpc.lsdfms.model.CdrFormat;
import com.hpc.lsdfms.model.CdrPayload;
import com.hpc.lsdfms.model.ColumnIndex;
import com.hpc.lsdfms.search.ColumnSearch;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Servicio en proceso sobre un dataset: posee un único {@link DatasetAccess} durante toda su
 * vida y serializa todas las operaciones.
 *
 * <p><b>Mutaciones.</b> Alta, actualización y baja se encolan en una {@link SyncQueue} y un
 * único hilo aplicador las ejecuta en orden total; cada petición lleva un {@link ResultSlot}
 * que el invocador espera con plazo. {@link #queueDepth()} cuenta las pendientes y la que
 * se está aplicando.
 *
 * <p><b>Versiones.</b> {@code dataVersion} sube una vez por mutación exitosa;
 * {@code indexVersion} es la {@code dataVersion} capturada al iniciar el último ciclo de
 * mantenimiento. El dataset está desactualizado ({@code stale}) si difieren.
 *
 * <p><b>Delta.</b> Los registros agregados desde la última reindexación se guardan como
 * (fileId, offset) en memoria. Una búsqueda combina los resultados del índice
 * ({@code source="index"}, sin borrados) con las coincidencias del delta ({@code "delta"}),
 * respetando el límite. Las bajas retiran el registro del delta.
 *
 * <p><b>Guardia.</b> Un {@link ReadWriteGuard} justo (orden de llegada): las búsquedas toman
 * acceso compartido; el aplicador toma acceso exclusivo por mutación (así una búsqueda nunca
 * ve una actualización a medias); el mantenimiento toma acceso exclusivo durante todo el PACK
 * y la reindexación, de modo que ninguna búsqueda ni mutación ve offsets en movimiento. Una
 * mutación enviada durante PACK espera y se aplica al terminar.
 *
 * <p><b>Contadores.</b> total y borrados se calculan escaneando cabeceras al abrir y se
 * mantienen de forma incremental (alta: total+1; baja: borrados+1; actualización: ambos);
 * tras PACK, total pasa a ser los activos y borrados a cero.
 *
 * <p><b>Apertura.</b> Si no existen los tres índices y no hay marcador, se construyen. Si hay
 * marcador de mantenimiento pendiente se marca {@code needsRecover}: las operaciones sobre
 * el dataset fallan hasta que {@link #requestMaintenance()} ejecuta la recuperación.
 */
public final class DatasetService implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 1024;
    private static final List<String> INDEX_NAMES = List.of("phone", "timestamp", "size");

    /** Vista de un registro tal como la expone la API. */
    public record RecordView(int fileId, long offset, long phone, long timestamp, int payloadLen,
            String service, int cell, int duration, String roaming, String source) { }

    public record SearchResult(long indexVersion, List<RecordView> results, boolean truncated) { }

    /** {@code previous} es la ubicación reemplazada (solo en actualizaciones). */
    public record MutationResult(RecordWriter.Location previous, RecordView record,
            long dataVersion) { }

    public record LastMaintenance(long at, long durationMs, long removedRecords,
            List<String> indexes) { }

    public record Status(long dataVersion, long indexVersion, boolean stale, int files,
            long totalRecords, long activeRecords, long deletedRecords, int queueDepth,
            boolean maintenanceRunning, boolean needsRecover, LastMaintenance lastMaintenance) { }

    @FunctionalInterface
    private interface Mutation {
        Object apply() throws Exception;
    }

    private record Request(Mutation mutation, ResultSlot<Object> slot) { }

    private final DatasetAccess access;
    private final RecordWriter writer;
    private final SequentialEngine engine;
    private final Path marker;
    private final int files;
    private final SyncQueue<Request> queue;
    private final ReadWriteGuard guard = new ReadWriteGuard();
    private final MaintenanceWorker worker;
    private final Thread applier;
    // Todo el estado siguiente se protege con el monitor "state". Nunca se invoca al
    // trabajador ni a la cola mientras se sostiene "state".
    private final Object state = new Object();
    private final List<RecordWriter.Location> delta = new ArrayList<>();
    private long dataVersion;
    private long indexVersion;
    private long totalRecords;
    private long deletedRecords;
    private int inFlight;
    private boolean needsRecover;
    private String maintenanceError;
    private LastMaintenance lastMaintenance;
    private boolean closed;

    /** Abre el dataset con el debounce indicado (0 = mantenimiento inmediato). */
    public static DatasetService open(Path directory, long debounceMs) throws IOException {
        return open(directory, debounceMs, new SequentialEngine.Operations() { });
    }

    static DatasetService open(Path directory, long debounceMs,
            SequentialEngine.Operations operations) throws IOException {
        return new DatasetService(directory, debounceMs, operations);
    }

    private DatasetService(Path directory, long debounceMs, SequentialEngine.Operations operations)
            throws IOException {
        if (debounceMs < 0) {
            throw new IllegalArgumentException("El debounce no puede ser negativo: " + debounceMs);
        }
        this.access = DatasetAccess.acquire(directory);
        try {
            this.writer = new RecordWriter(access);
            this.engine = new SequentialEngine(operations);
            this.marker = access.directory().resolve(DatasetAccess.MAINTENANCE_NAME);
            var sources = DatasetFiles.list(access.directory());
            this.files = sources.size();
            for (var source : sources) {
                var stats = BinaryFileHandler.inspect(source.path());
                totalRecords += stats.records();
                deletedRecords += stats.deleted();
            }
            needsRecover = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
            if (!needsRecover && missingIndexes()) {
                new ColumnIndexBuilder().buildLocked(access);
            }
        } catch (IOException | RuntimeException e) {
            try {
                access.close();
            } catch (IOException closeError) {
                e.addSuppressed(closeError);
            }
            throw e;
        }
        this.queue = new SyncQueue<>(DEFAULT_QUEUE_CAPACITY);
        this.worker = new MaintenanceWorker(this::runMaintenanceCycle, this::automaticDue, debounceMs);
        this.applier = new Thread(this::applyLoop, "lsdfms-applier");
        this.applier.setDaemon(true);
        this.applier.start();
        this.worker.start();
    }

    // ---------------------------------------------------------------- mutaciones

    /** Alta con payload mínimo (64 bytes). */
    public MutationResult insert(long phone, long timestamp, CdrPayload payload, long timeoutMs)
            throws InterruptedException, ResultSlot.FailedException, ResultSlot.TimedOutException {
        return insert(phone, timestamp, payload, CdrPayload.MIN_BYTES, timeoutMs);
    }

    public MutationResult insert(long phone, long timestamp, CdrPayload payload, int payloadBytes,
            long timeoutMs)
            throws InterruptedException, ResultSlot.FailedException, ResultSlot.TimedOutException {
        return submit(() -> applyInsert(phone, timestamp, payload, payloadBytes), timeoutMs);
    }

    /** Reemplaza el registro en (fileId, offset); conserva el tamaño del payload anterior. */
    public MutationResult update(int fileId, long offset, long phone, long timestamp,
            CdrPayload payload, long timeoutMs)
            throws InterruptedException, ResultSlot.FailedException, ResultSlot.TimedOutException {
        return submit(() -> applyUpdate(fileId, offset, null, phone, timestamp, payload), timeoutMs);
    }

    /**
     * Como {@link #update} pero falla con {@link IllegalStateException} si el teléfono del
     * registro vigente en esa ubicación no es {@code expectedPhone} (referencia obsoleta tras
     * un PACK).
     */
    public MutationResult updateChecked(int fileId, long offset, long expectedPhone, long phone,
            long timestamp, CdrPayload payload, long timeoutMs)
            throws InterruptedException, ResultSlot.FailedException, ResultSlot.TimedOutException {
        return submit(() -> applyUpdate(fileId, offset, expectedPhone, phone, timestamp, payload),
                timeoutMs);
    }

    /** Baja lógica; devuelve la {@code dataVersion} resultante. */
    public long delete(int fileId, long offset, long timeoutMs)
            throws InterruptedException, ResultSlot.FailedException, ResultSlot.TimedOutException {
        return submit(() -> applyDelete(fileId, offset, null), timeoutMs);
    }

    /** Como {@link #delete} con verificación del teléfono esperado (referencia obsoleta). */
    public long deleteChecked(int fileId, long offset, long expectedPhone, long timeoutMs)
            throws InterruptedException, ResultSlot.FailedException, ResultSlot.TimedOutException {
        return submit(() -> applyDelete(fileId, offset, expectedPhone), timeoutMs);
    }

    @SuppressWarnings("unchecked")
    private <T> T submit(Mutation mutation, long timeoutMs)
            throws InterruptedException, ResultSlot.FailedException, ResultSlot.TimedOutException {
        ResultSlot<Object> slot = new ResultSlot<>();
        try {
            queue.put(new Request(mutation, slot));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("El servicio está cerrado", e);
        }
        return (T) slot.await(timeoutMs);
    }

    private void applyLoop() {
        while (true) {
            Request request;
            try {
                request = queue.take();
            } catch (InterruptedException e) {
                return;
            }
            if (request == null) {
                return;
            }
            synchronized (state) {
                inFlight = 1;
            }
            guard.acquireWrite();
            try {
                request.slot().complete(request.mutation().apply());
            } catch (Throwable t) {
                request.slot().fail(t);
            } finally {
                guard.releaseWrite();
                synchronized (state) {
                    inFlight = 0;
                }
            }
            worker.mutated();
        }
    }

    private MutationResult applyInsert(long phone, long timestamp, CdrPayload payload,
            int payloadBytes) throws IOException {
        byte[] bytes = payload.build(payloadBytes);
        RecordWriter.Location at = writer.append(phone, timestamp, bytes);
        long version;
        synchronized (state) {
            delta.add(at);
            totalRecords++;
            version = ++dataVersion;
        }
        return new MutationResult(null, view(at, phone, timestamp, bytes.length, payload, "delta"),
                version);
    }

    private MutationResult applyUpdate(int fileId, long offset, Long expectedPhone, long phone,
            long timestamp, CdrPayload payload) throws IOException {
        BinaryFileHandler.Header old = requireActive(fileId, offset, expectedPhone);
        byte[] bytes = payload.build(Math.max(CdrPayload.MIN_BYTES, old.payloadLength()));
        RecordWriter.Location previous = new RecordWriter.Location(fileId, offset);
        RecordWriter.Location at = writer.append(phone, timestamp, bytes);
        synchronized (state) {
            delta.add(at);
            totalRecords++;
        }
        try {
            writer.markDeleted(fileId, offset, old.phoneNumber());
        } catch (IOException | RuntimeException e) {
            synchronized (state) { // El alta ya existe: se refleja aunque falle la baja anterior.
                dataVersion++;
            }
            throw e;
        }
        long version;
        synchronized (state) {
            delta.remove(previous);
            deletedRecords++;
            version = ++dataVersion;
        }
        return new MutationResult(previous,
                view(at, phone, timestamp, bytes.length, payload, "delta"), version);
    }

    private BinaryFileHandler.Header requireActive(int fileId, long offset, Long expectedPhone)
            throws IOException {
        BinaryFileHandler.Header header = readHeader(fileId, offset);
        if (header.deleted()) {
            throw new IllegalStateException("El registro ya está borrado: F_" + fileId
                    + ".dat @ " + offset);
        }
        if (expectedPhone != null && header.phoneNumber() != expectedPhone) {
            throw new IllegalStateException("El teléfono no coincide en F_" + fileId + ".dat @ "
                    + offset + " (referencia obsoleta)");
        }
        return header;
    }

    private Long applyDelete(int fileId, long offset, Long expectedPhone) throws IOException {
        BinaryFileHandler.Header header = requireActive(fileId, offset, expectedPhone);
        writer.markDeleted(fileId, offset, header.phoneNumber());
        synchronized (state) {
            delta.remove(new RecordWriter.Location(fileId, offset));
            deletedRecords++;
            return ++dataVersion;
        }
    }

    private BinaryFileHandler.Header readHeader(int fileId, long offset) throws IOException {
        Path path = dataFile(fileId);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            if (offset < 0 || offset > channel.size() - CdrFormat.HEADER_BYTES) {
                throw new IllegalArgumentException("Offset fuera de límites: F_" + fileId
                        + ".dat @ " + offset);
            }
            return BinaryFileHandler.readHeader(channel, path, offset);
        } catch (IOException e) {
            if (e.getClass() == IOException.class) { // Cabecera inválida: ubicación obsoleta.
                throw new IllegalArgumentException("El offset no apunta a un registro válido: F_"
                        + fileId + ".dat @ " + offset, e);
            }
            throw e;
        }
    }

    private Path dataFile(int fileId) {
        Path path = access.directory().resolve("F_" + fileId + ".dat");
        if (fileId <= 0 || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("No existe F_" + fileId + ".dat");
        }
        return path;
    }

    // ---------------------------------------------------------------- búsqueda

    /**
     * Busca en el índice de {@code column} el rango cerrado [lo, hi] (exacto si lo == hi) y
     * añade las coincidencias del delta. {@code limit} debe ser al menos 1.
     */
    public SearchResult search(ColumnIndex column, long lo, long hi, int limit)
            throws IOException {
        guard.acquireRead();
        try {
            List<ColumnSearch.Hit> indexHits = new ArrayList<>();
            ColumnSearch.Result found = new ColumnSearch().searchLocked(access, column, lo, hi,
                    limit, indexHits::add);
            List<RecordView> views = new ArrayList<>();
            for (ColumnSearch.Hit hit : indexHits) {
                views.add(materialize(hit, "index"));
            }
            boolean truncated = found.truncated();
            if (!truncated) {
                List<ColumnSearch.Hit> fresh = deltaHits(column, lo, hi);
                for (ColumnSearch.Hit hit : fresh) {
                    if (views.size() == limit) {
                        truncated = true;
                        break;
                    }
                    views.add(materialize(hit, "delta"));
                }
            }
            long version;
            synchronized (state) {
                version = indexVersion;
            }
            return new SearchResult(version, views, truncated);
        } finally {
            guard.releaseRead();
        }
    }

    private List<ColumnSearch.Hit> deltaHits(ColumnIndex column, long lo, long hi)
            throws IOException {
        List<RecordWriter.Location> snapshot;
        synchronized (state) {
            snapshot = new ArrayList<>(delta);
        }
        Map<Integer, FileChannel> open = new HashMap<>();
        List<ColumnSearch.Hit> hits = new ArrayList<>();
        try {
            for (RecordWriter.Location at : snapshot) {
                Path path = dataFile(at.fileId());
                FileChannel channel = open.get(at.fileId());
                if (channel == null) {
                    channel = FileChannel.open(path, StandardOpenOption.READ,
                            LinkOption.NOFOLLOW_LINKS);
                    open.put(at.fileId(), channel);
                }
                BinaryFileHandler.Header header = BinaryFileHandler.readHeader(channel, path,
                        at.offset());
                long key = switch (column) {
                    case PHONE -> header.phoneNumber();
                    case TIMESTAMP -> header.timestamp();
                    case SIZE -> header.payloadLength();
                };
                if (!header.deleted() && key >= lo && key <= hi) {
                    hits.add(new ColumnSearch.Hit(at.fileId(), at.offset(), header.phoneNumber(),
                            header.timestamp(), header.payloadLength()));
                }
            }
        } finally {
            for (FileChannel channel : open.values()) {
                channel.close();
            }
        }
        hits.sort(Comparator.comparingLong((ColumnSearch.Hit h) -> switch (column) {
            case PHONE -> h.phone();
            case TIMESTAMP -> h.timestamp();
            case SIZE -> h.payloadLen();
        }).thenComparingInt(ColumnSearch.Hit::fileId).thenComparingLong(ColumnSearch.Hit::offset));
        return hits;
    }

    /** Lee el payload y arma la vista; un payload no interpretable usa valores por defecto. */
    private RecordView materialize(ColumnSearch.Hit hit, String source) throws IOException {
        var record = BinaryFileHandler.readActive(dataFile(hit.fileId()), hit.offset(), hit.phone());
        CdrPayload payload = null;
        try {
            payload = CdrPayload.parse(record.payload());
        } catch (IllegalArgumentException e) {
            // Payload ajeno al formato CDR: se expone con campos vacíos.
        }
        return new RecordView(hit.fileId(), hit.offset(), hit.phone(), hit.timestamp(),
                hit.payloadLen(), payload == null ? "" : payload.service(),
                payload == null ? 0 : payload.cell(), payload == null ? 0 : payload.duration(),
                payload == null ? "" : payload.roaming(), source);
    }

    private static RecordView view(RecordWriter.Location at, long phone, long timestamp,
            int length, CdrPayload payload, String source) {
        return new RecordView(at.fileId(), at.offset(), phone, timestamp, length,
                payload.service(), payload.cell(), payload.duration(), payload.roaming(), source);
    }

    // ---------------------------------------------------------------- mantenimiento

    /** Solicita un ciclo de mantenimiento inmediato (PACK y reindexación, o recuperación). */
    public void requestMaintenance() {
        worker.request();
    }

    /** Espera a que no haya mantenimiento en curso, solicitado ni automático pendiente. */
    public boolean awaitMaintenanceIdle(long timeoutMs) throws InterruptedException {
        return worker.awaitIdle(timeoutMs);
    }

    private boolean automaticDue() {
        synchronized (state) {
            return dataVersion != indexVersion && !needsRecover;
        }
    }

    private void runMaintenanceCycle() {
        long started = System.nanoTime();
        guard.acquireWrite();
        try {
            long captured;
            long totalBefore;
            synchronized (state) {
                captured = dataVersion;
                totalBefore = totalRecords;
            }
            boolean recovery = Files.exists(marker, LinkOption.NOFOLLOW_LINKS);
            SequentialEngine.PackStats stats = engine.packLocked(access, recovery);
            long durationMs = (System.nanoTime() - started) / 1_000_000L;
            synchronized (state) {
                indexVersion = captured; // Con acceso exclusivo, el delta completo entró al índice.
                delta.clear();
                totalRecords = stats.activeRecords();
                deletedRecords = 0;
                needsRecover = false;
                maintenanceError = null;
                lastMaintenance = new LastMaintenance(System.currentTimeMillis(), durationMs,
                        totalBefore - stats.activeRecords(), INDEX_NAMES);
            }
        } catch (Throwable t) {
            synchronized (state) {
                needsRecover = true;
                maintenanceError = t.toString();
            }
        } finally {
            guard.releaseWrite();
        }
    }

    // ---------------------------------------------------------------- estado

    public Status status() {
        Status partial;
        synchronized (state) {
            partial = new Status(dataVersion, indexVersion, dataVersion != indexVersion, files,
                    totalRecords, totalRecords - deletedRecords, deletedRecords, inFlight,
                    false, needsRecover, lastMaintenance);
        }
        return new Status(partial.dataVersion(), partial.indexVersion(), partial.stale(),
                partial.files(), partial.totalRecords(), partial.activeRecords(),
                partial.deletedRecords(), partial.queueDepth() + queue.size(),
                worker.isActive(), partial.needsRecover(), partial.lastMaintenance());
    }

    public int queueDepth() {
        int running;
        synchronized (state) {
            running = inFlight;
        }
        return running + queue.size();
    }

    /** Descripción del último fallo de mantenimiento, o {@code null}. */
    public String lastMaintenanceError() {
        synchronized (state) {
            return maintenanceError;
        }
    }

    private boolean missingIndexes() {
        for (ColumnIndex column : ColumnIndex.values()) {
            if (!Files.isRegularFile(access.directory().resolve(column.fileName()),
                    LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- cierre

    /** Detiene aplicador y trabajador (terminan lo en curso) y libera el acceso al dataset. */
    @Override
    public void close() throws IOException {
        synchronized (state) {
            if (closed) {
                return;
            }
            closed = true;
        }
        queue.close();
        boolean interrupted = false;
        try {
            applier.join();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        try {
            worker.close();
        } catch (InterruptedException e) {
            interrupted = true;
        }
        try {
            access.close();
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
