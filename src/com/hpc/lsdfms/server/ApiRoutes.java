package com.hpc.lsdfms.server;

import com.hpc.lsdfms.core.DatasetService;
import com.hpc.lsdfms.model.CdrPayload;
import com.hpc.lsdfms.model.ColumnIndex;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Endpoints {@code /api/*}: validan la entrada y delegan en {@link DatasetService}. */
public final class ApiRoutes {
    static final long MUTATION_TIMEOUT_MS = 10_000;
    static final int DEFAULT_LIMIT = 100;
    static final int MAX_LIMIT = 500;

    private final DatasetService service;

    public ApiRoutes(DatasetService service) {
        this.service = service;
    }

    public void register(Router router) {
        router.route("GET", "/api/status", r -> HttpResponse.json(200, status(service.status())));
        router.route("GET", "/api/search", this::search);
        router.route("POST", "/api/records", this::insert);
        router.route("PUT", "/api/records", this::update);
        router.route("DELETE", "/api/records", this::delete);
        router.route("POST", "/api/maintenance", r -> {
            service.requestMaintenance();
            return HttpResponse.json(202, Map.of("scheduled", true));
        });
    }

    private HttpResponse search(HttpRequest request) throws Exception {
        Map<String, String> q = request.query();
        ColumnIndex column = ColumnIndex.parse(requiredQuery(q, "col"));
        long lo = queryLong(q, "lo");
        long hi = queryLong(q, "hi");
        if (lo > hi) {
            throw new IllegalArgumentException("lo no puede ser mayor que hi");
        }
        int limit = q.containsKey("limit") ? (int) queryRange(q, "limit", 1, MAX_LIMIT) : DEFAULT_LIMIT;
        var found = service.search(column, lo, hi, limit);
        List<Object> results = new ArrayList<>();
        found.results().forEach(v -> results.add(record(v)));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("indexVersion", found.indexVersion());
        out.put("results", results);
        out.put("truncated", found.truncated());
        return HttpResponse.json(200, out);
    }

    private HttpResponse insert(HttpRequest request) throws Exception {
        Map<String, Object> body = body(request);
        var result = service.insert(longField(body, "phone"), longField(body, "timestamp"),
                payload(body), MUTATION_TIMEOUT_MS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("record", record(result.record()));
        out.put("dataVersion", result.dataVersion());
        return HttpResponse.json(201, out);
    }

    private HttpResponse update(HttpRequest request) throws Exception {
        Map<String, Object> body = body(request);
        int fileId = (int) rangeField(body, "fileId", 1, Integer.MAX_VALUE);
        long offset = rangeField(body, "offset", 0, Long.MAX_VALUE);
        var result = service.update(fileId, offset, longField(body, "phone"),
                longField(body, "timestamp"), payload(body), MUTATION_TIMEOUT_MS);
        Map<String, Object> old = new LinkedHashMap<>();
        old.put("fileId", result.previous().fileId());
        old.put("offset", result.previous().offset());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("old", old);
        out.put("record", record(result.record()));
        out.put("dataVersion", result.dataVersion());
        return HttpResponse.json(200, out);
    }

    private HttpResponse delete(HttpRequest request) throws Exception {
        int fileId = (int) queryRange(request.query(), "fileId", 1, Integer.MAX_VALUE);
        long offset = queryRange(request.query(), "offset", 0, Long.MAX_VALUE);
        long version = service.delete(fileId, offset, MUTATION_TIMEOUT_MS);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deleted", true);
        out.put("dataVersion", version);
        return HttpResponse.json(200, out);
    }

    // ---------------------------------------------------------------- vistas JSON

    static Map<String, Object> status(DatasetService.Status s) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dataVersion", s.dataVersion());
        out.put("indexVersion", s.indexVersion());
        out.put("stale", s.stale());
        out.put("files", s.files());
        out.put("totalRecords", s.totalRecords());
        out.put("activeRecords", s.activeRecords());
        out.put("deletedRecords", s.deletedRecords());
        out.put("queueDepth", s.queueDepth());
        out.put("maintenanceRunning", s.maintenanceRunning());
        out.put("needsRecover", s.needsRecover());
        var last = s.lastMaintenance();
        if (last == null) {
            out.put("lastMaintenance", null);
        } else {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("at", last.at());
            m.put("durationMs", last.durationMs());
            m.put("removedRecords", last.removedRecords());
            m.put("indexes", new ArrayList<Object>(last.indexes()));
            out.put("lastMaintenance", m);
        }
        return out;
    }

    static Map<String, Object> record(DatasetService.RecordView v) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fileId", v.fileId());
        out.put("offset", v.offset());
        out.put("phone", v.phone());
        out.put("timestamp", v.timestamp());
        out.put("payloadLen", v.payloadLen());
        out.put("service", v.service());
        out.put("cell", v.cell());
        out.put("duration", v.duration());
        out.put("roaming", v.roaming());
        out.put("source", v.source());
        return out;
    }

    // ---------------------------------------------------------------- validación

    private static Map<String, Object> body(HttpRequest request) {
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(request.body())).toString();
            return Json.parseObject(text);
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("El cuerpo no es UTF-8 válido");
        }
    }

    private static CdrPayload payload(Map<String, Object> body) {
        return new CdrPayload(stringField(body, "service"), (int) rangeField(body, "cell", 0, Integer.MAX_VALUE),
                (int) rangeField(body, "duration", 0, Integer.MAX_VALUE), stringField(body, "roaming"));
    }

    private static String stringField(Map<String, Object> body, String key) {
        if (body.get(key) instanceof String s) {
            return s;
        }
        throw new IllegalArgumentException("El campo " + key + " es obligatorio y debe ser texto");
    }

    private static long longField(Map<String, Object> body, String key) {
        if (body.get(key) instanceof Long n) {
            return n;
        }
        throw new IllegalArgumentException("El campo " + key + " es obligatorio y debe ser un entero");
    }

    private static long rangeField(Map<String, Object> body, String key, long min, long max) {
        long value = longField(body, key);
        if (value < min || value > max) {
            throw new IllegalArgumentException("El campo " + key + " está fuera de rango");
        }
        return value;
    }

    private static String requiredQuery(Map<String, String> q, String key) {
        String value = q.get(key);
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException("Falta el parámetro " + key);
        }
        return value;
    }

    private static long queryLong(Map<String, String> q, String key) {
        try {
            return Long.parseLong(requiredQuery(q, key));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("El parámetro " + key + " debe ser un entero");
        }
    }

    private static long queryRange(Map<String, String> q, String key, long min, long max) {
        long value = queryLong(q, key);
        if (value < min || value > max) {
            throw new IllegalArgumentException("El parámetro " + key + " debe estar entre " + min + " y " + max);
        }
        return value;
    }
}
