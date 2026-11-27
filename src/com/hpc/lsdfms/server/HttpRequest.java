package com.hpc.lsdfms.server;

import java.util.Map;

/** Petición ya analizada: método, ruta decodificada, parámetros de consulta, cabeceras y cuerpo. */
public record HttpRequest(String method, String path, Map<String, String> query,
        Map<String, String> headers, byte[] body) { }
