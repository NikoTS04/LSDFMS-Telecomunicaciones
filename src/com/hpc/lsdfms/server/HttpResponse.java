package com.hpc.lsdfms.server;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Respuesta lista para serializar; el cuerpo ya está codificado en bytes. */
public record HttpResponse(int status, String contentType, byte[] body, Map<String, String> headers) {
    public static HttpResponse json(int status, Object value) {
        return new HttpResponse(status, "application/json; charset=utf-8",
                Json.write(value).getBytes(StandardCharsets.UTF_8), Map.of("Cache-Control", "no-store"));
    }

    public static HttpResponse error(int status, String message) {
        return json(status, Map.of("error", message));
    }

    public static String reason(int status) {
        return switch (status) {
            case 100 -> "Continue";
            case 200 -> "OK";
            case 201 -> "Created";
            case 202 -> "Accepted";
            case 400 -> "Bad Request";
            case 404 -> "Not Found";
            case 405 -> "Method Not Allowed";
            case 408 -> "Request Timeout";
            case 409 -> "Conflict";
            case 413 -> "Payload Too Large";
            case 431 -> "Request Header Fields Too Large";
            case 500 -> "Internal Server Error";
            case 503 -> "Service Unavailable";
            default -> "Status";
        };
    }
}
