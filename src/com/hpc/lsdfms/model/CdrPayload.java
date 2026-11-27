package com.hpc.lsdfms.model;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Payload textual de un registro:
 * {@code service=..;cell=..;duration=..;roaming=..;meta=<relleno hexadecimal>}.
 */
public record CdrPayload(String service, int cell, int duration, String roaming) {
    public static final int MIN_BYTES = 64;
    public static final int MAX_BYTES = 1_048_576;
    public static final Set<String> SERVICES = Set.of("VOZ", "SMS", "DATA");
    public static final Set<String> ROAMING = Set.of("PE", "CL", "CO", "EC", "US");

    public CdrPayload {
        if (service == null || !SERVICES.contains(service)) {
            throw new IllegalArgumentException("service debe ser VOZ, SMS o DATA: " + service);
        }
        if (cell < 10000 || cell > 99999) {
            throw new IllegalArgumentException("cell debe estar entre 10000 y 99999: " + cell);
        }
        if (duration < 0 || duration > 3600) {
            throw new IllegalArgumentException("duration debe estar entre 0 y 3600: " + duration);
        }
        if (roaming == null || !ROAMING.contains(roaming)) {
            throw new IllegalArgumentException("roaming debe ser PE, CL, CO, EC o US: " + roaming);
        }
    }

    /** Construye un payload de {@link #MIN_BYTES} bytes. */
    public byte[] build() {
        return build(MIN_BYTES);
    }

    /** Construye un payload ASCII de exactamente {@code totalBytes} bytes (64..1048576). */
    public byte[] build(int totalBytes) {
        if (totalBytes < MIN_BYTES || totalBytes > MAX_BYTES) {
            throw new IllegalArgumentException("El payload debe medir entre " + MIN_BYTES
                    + " y " + MAX_BYTES + " bytes: " + totalBytes);
        }
        byte[] prefix = ("service=" + service + ";cell=" + cell + ";duration=" + duration
                + ";roaming=" + roaming + ";meta=").getBytes(StandardCharsets.US_ASCII);
        byte[] result = new byte[totalBytes];
        System.arraycopy(prefix, 0, result, 0, prefix.length);
        for (int i = prefix.length; i < totalBytes; i++) {
            result[i] = (byte) '0';
        }
        return result;
    }

    /** Extrae los campos de un payload; ignora {@code meta} y claves desconocidas. */
    public static CdrPayload parse(byte[] payload) {
        String text = new String(payload, StandardCharsets.US_ASCII);
        String service = null;
        String roaming = null;
        Integer cell = null;
        Integer duration = null;
        for (String part : text.split(";", -1)) {
            int eq = part.indexOf('=');
            if (eq < 0) {
                continue;
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            switch (key) {
                case "service" -> service = value;
                case "roaming" -> roaming = value;
                case "cell" -> cell = number(key, value);
                case "duration" -> duration = number(key, value);
                case "meta" -> { }
                default -> { }
            }
            if ("meta".equals(key)) {
                break; // El relleno puede ser extenso; no hay campos después de meta.
            }
        }
        if (service == null || cell == null || duration == null || roaming == null) {
            throw new IllegalArgumentException("Payload sin los campos service/cell/duration/roaming");
        }
        return new CdrPayload(service, cell, duration, roaming);
    }

    private static int number(String key, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Valor numérico inválido para " + key + ": " + value, e);
        }
    }
}
