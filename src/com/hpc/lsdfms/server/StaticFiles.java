package com.hpc.lsdfms.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;

/** Sirve {@code index.html} en "/" y archivos de un directorio bajo "/static/". */
public final class StaticFiles {
    public static final String PREFIX = "/static/";

    private final Path root;

    public StaticFiles(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public HttpResponse index(HttpRequest request) throws IOException {
        return serve("index.html");
    }

    public HttpResponse file(HttpRequest request) throws IOException {
        return serve(request.path().substring(PREFIX.length()));
    }

    private HttpResponse serve(String name) throws IOException {
        if (name.isEmpty() || name.indexOf('\0') >= 0 || name.indexOf('\\') >= 0) {
            return HttpResponse.error(404, "Archivo no encontrado");
        }
        for (String segment : name.split("/", -1)) {
            if (segment.isEmpty() || segment.startsWith(".")) {
                return HttpResponse.error(404, "Archivo no encontrado");
            }
        }
        Path file = root.resolve(name).normalize();
        if (!file.startsWith(root) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return HttpResponse.error(404, "Archivo no encontrado");
        }
        return new HttpResponse(200, contentType(name), Files.readAllBytes(file),
                Map.of("Cache-Control", "no-cache"));
    }

    static String contentType(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (lower.endsWith(".js")) {
            return "text/javascript; charset=utf-8";
        }
        if (lower.endsWith(".json")) {
            return "application/json; charset=utf-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".png")) {
            return "image/png";
        }
        if (lower.endsWith(".ico")) {
            return "image/x-icon";
        }
        return "application/octet-stream";
    }
}
