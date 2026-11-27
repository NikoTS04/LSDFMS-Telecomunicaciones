package com.hpc.lsdfms.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/** Analizador mínimo de peticiones HTTP/1.1: línea de petición, cabeceras y Content-Length. */
public final class HttpParser {
    public static final int MAX_HEADER_BYTES = 16 * 1024;
    public static final int MAX_BODY_BYTES = 1024 * 1024;

    private HttpParser() { }

    /**
     * Lee una petición. Devuelve {@code null} si la conexión se cerró antes de enviar datos.
     * Lanza {@link HttpException} (400, 413, 431, 501) ante peticiones inválidas.
     */
    public static HttpRequest parse(InputStream in, OutputStream interim) throws IOException {
        byte[] head = readHead(in);
        if (head == null) {
            return null;
        }
        String[] lines = new String(head, StandardCharsets.ISO_8859_1).split("\r\n", -1);
        String[] parts = lines[0].split(" ", -1);
        if (parts.length != 3 || parts[0].isEmpty() || !parts[2].startsWith("HTTP/1.")
                || !parts[1].startsWith("/")) {
            throw new HttpException(400, "Línea de petición inválida");
        }
        Map<String, String> headers = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                continue;
            }
            int colon = lines[i].indexOf(':');
            if (colon <= 0 || lines[i].charAt(0) == ' ' || lines[i].charAt(0) == '\t') {
                throw new HttpException(400, "Cabecera inválida");
            }
            headers.put(lines[i].substring(0, colon).toLowerCase(java.util.Locale.ROOT),
                    lines[i].substring(colon + 1).trim());
        }
        if (headers.containsKey("transfer-encoding")) {
            throw new HttpException(501, "Transfer-Encoding no está soportado");
        }
        int length = 0;
        String declared = headers.get("content-length");
        if (declared != null) {
            try {
                long value = Long.parseLong(declared);
                if (value < 0) {
                    throw new NumberFormatException(declared);
                }
                if (value > MAX_BODY_BYTES) {
                    throw new HttpException(413, "El cuerpo supera " + MAX_BODY_BYTES + " bytes");
                }
                length = (int) value;
            } catch (NumberFormatException e) {
                throw new HttpException(400, "Content-Length inválido");
            }
        }
        if (length > 0 && "100-continue".equalsIgnoreCase(headers.get("expect")) && interim != null) {
            interim.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            interim.flush();
        }
        byte[] body = in.readNBytes(length);
        if (body.length != length) {
            throw new HttpException(400, "Cuerpo incompleto");
        }
        String target = parts[1];
        int mark = target.indexOf('?');
        String rawPath = mark < 0 ? target : target.substring(0, mark);
        Map<String, String> query = mark < 0 ? Map.of() : parseQuery(target.substring(mark + 1));
        return new HttpRequest(parts[0], decode(rawPath.replace("+", "%2B")), query, headers, body);
    }

    private static byte[] readHead(InputStream in) throws IOException {
        byte[] buffer = new byte[MAX_HEADER_BYTES];
        int size = 0;
        while (true) {
            int b = in.read();
            if (b < 0) {
                if (size == 0) {
                    return null;
                }
                throw new HttpException(400, "Petición incompleta");
            }
            if (size == buffer.length) {
                throw new HttpException(431, "Las cabeceras superan " + MAX_HEADER_BYTES + " bytes");
            }
            buffer[size++] = (byte) b;
            if (size >= 4 && buffer[size - 4] == '\r' && buffer[size - 3] == '\n'
                    && buffer[size - 2] == '\r' && buffer[size - 1] == '\n') {
                return java.util.Arrays.copyOf(buffer, size - 4);
            }
        }
    }

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> result = new HashMap<>();
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String key = decode(eq < 0 ? pair : pair.substring(0, eq));
            String value = eq < 0 ? "" : decode(pair.substring(eq + 1));
            result.put(key, value);
        }
        return result;
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new HttpException(400, "Codificación porcentual inválida");
        }
    }
}
