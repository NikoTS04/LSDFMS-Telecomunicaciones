package com.hpc.lsdfms.server;

import com.hpc.lsdfms.core.ResultSlot;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Enruta por método y ruta exacta (o prefijo para GET) y traduce excepciones a respuestas JSON. */
public final class Router {
    @FunctionalInterface
    public interface Handler {
        HttpResponse handle(HttpRequest request) throws Exception;
    }

    private record Prefix(String prefix, Handler handler) { }

    private final Map<String, Map<String, Handler>> exact = new LinkedHashMap<>();
    private final List<Prefix> prefixes = new ArrayList<>();

    public Router route(String method, String path, Handler handler) {
        exact.computeIfAbsent(path, p -> new LinkedHashMap<>()).put(method, handler);
        return this;
    }

    /** Registra un manejador GET para todas las rutas que empiecen con {@code prefix}. */
    public Router prefix(String prefix, Handler handler) {
        prefixes.add(new Prefix(prefix, handler));
        return this;
    }

    public HttpResponse dispatch(HttpRequest request) {
        try {
            Map<String, Handler> methods = exact.get(request.path());
            if (methods != null) {
                Handler handler = methods.get(request.method());
                if (handler == null) {
                    return notAllowed(methods.keySet());
                }
                return handler.handle(request);
            }
            for (Prefix prefix : prefixes) {
                if (request.path().startsWith(prefix.prefix())) {
                    return "GET".equals(request.method()) ? prefix.handler().handle(request)
                            : notAllowed(Set.of("GET"));
                }
            }
            return HttpResponse.error(404, "Ruta no encontrada: " + request.path());
        } catch (Exception e) {
            return translate(e);
        }
    }

    private static HttpResponse notAllowed(Set<String> allowed) {
        HttpResponse base = HttpResponse.error(405, "Método no permitido");
        return new HttpResponse(405, base.contentType(), base.body(),
                Map.of("Allow", String.join(", ", new LinkedHashSet<>(allowed)),
                        "Cache-Control", "no-store"));
    }

    /** Traduce una excepción a su estado HTTP; las fallas del aplicador se resuelven por su causa. */
    static HttpResponse translate(Throwable error) {
        if (error instanceof ResultSlot.FailedException failed && failed.getCause() != null) {
            error = failed.getCause();
        }
        if (error instanceof HttpException http) {
            return HttpResponse.error(http.status(), http.getMessage());
        }
        if (error instanceof IllegalArgumentException) {
            return HttpResponse.error(400, error.getMessage());
        }
        if (error instanceof IllegalStateException) {
            return HttpResponse.error(409, error.getMessage());
        }
        if (error instanceof ResultSlot.TimedOutException) {
            return HttpResponse.error(503, "La cola de mutaciones no respondió a tiempo");
        }
        if (error instanceof InterruptedException) {
            Thread.currentThread().interrupt();
            return HttpResponse.error(503, "Servidor en cierre");
        }
        if (error instanceof IOException) {
            return HttpResponse.error(500, "Error de E/S: " + error.getMessage());
        }
        return HttpResponse.error(500, "Error interno: " + error);
    }
}
