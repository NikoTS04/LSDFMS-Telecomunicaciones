package com.hpc.lsdfms.server;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON mínimo escrito a mano: serializador de Map/List/String/Number/Boolean/null y un analizador
 * de objetos planos (cadenas, enteros, booleanos y null). Todo lo demás se rechaza con
 * {@link IllegalArgumentException}.
 */
public final class Json {
    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    // ---------------------------------------------------------------- escritura

    public static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(out, value);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String s) {
            writeString(out, s);
        } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long) {
            out.append(value);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(out, String.valueOf(entry.getKey()));
                out.append(':');
                write(out, entry.getValue());
            }
            out.append('}');
        } else if (value instanceof List<?> list) {
            out.append('[');
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                write(out, list.get(i));
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Tipo no serializable: " + value.getClass().getName());
        }
    }

    private static void writeString(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20 || c == ' ' || c == ' ') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    // ---------------------------------------------------------------- lectura

    /** Analiza un objeto JSON plano: valores String, Long, Boolean o null. */
    public static Map<String, Object> parseObject(String text) {
        Json parser = new Json(text);
        parser.skipSpace();
        parser.expect('{');
        Map<String, Object> result = new LinkedHashMap<>();
        parser.skipSpace();
        if (parser.peek() == '}') {
            parser.pos++;
        } else {
            while (true) {
                parser.skipSpace();
                String key = parser.readString();
                parser.skipSpace();
                parser.expect(':');
                parser.skipSpace();
                Object value = parser.readValue();
                if (result.containsKey(key)) {
                    throw parser.fail("Clave duplicada: " + key);
                }
                result.put(key, value);
                parser.skipSpace();
                char next = parser.next();
                if (next == '}') {
                    break;
                }
                if (next != ',') {
                    throw parser.fail("Se esperaba ',' o '}'");
                }
            }
        }
        parser.skipSpace();
        if (parser.pos != text.length()) {
            throw parser.fail("Contenido tras el objeto");
        }
        return result;
    }

    private IllegalArgumentException fail(String message) {
        return new IllegalArgumentException("JSON inválido: " + message + " (posición " + pos + ")");
    }

    private char peek() {
        return pos < text.length() ? text.charAt(pos) : '\0';
    }

    private char next() {
        if (pos >= text.length()) {
            throw fail("fin inesperado");
        }
        return text.charAt(pos++);
    }

    private void expect(char c) {
        if (next() != c) {
            pos--;
            throw fail("se esperaba '" + c + "'");
        }
    }

    private void skipSpace() {
        while (pos < text.length() && " \t\r\n".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private Object readValue() {
        char c = peek();
        if (c == '"') {
            return readString();
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return readInteger();
        }
        if (text.startsWith("true", pos)) {
            pos += 4;
            return Boolean.TRUE;
        }
        if (text.startsWith("false", pos)) {
            pos += 5;
            return Boolean.FALSE;
        }
        if (text.startsWith("null", pos)) {
            pos += 4;
            return null;
        }
        throw fail("valor no admitido (solo cadenas, enteros, booleanos y null)");
    }

    private Long readInteger() {
        int start = pos;
        if (peek() == '-') {
            pos++;
        }
        int digits = pos;
        while (peek() >= '0' && peek() <= '9') {
            pos++;
        }
        if (pos == digits || (text.charAt(digits) == '0' && pos - digits > 1)) {
            throw fail("entero mal formado");
        }
        char after = peek();
        if (after == '.' || after == 'e' || after == 'E') {
            throw fail("solo se admiten enteros");
        }
        try {
            return Long.parseLong(text.substring(start, pos));
        } catch (NumberFormatException e) {
            throw fail("entero fuera del rango de long");
        }
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw fail("carácter de control sin escapar");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char e = next();
            switch (e) {
                case '"', '\\', '/' -> out.append(e);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw fail("escape \\u incompleto");
                    }
                    try {
                        out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw fail("escape \\u inválido");
                    }
                    pos += 4;
                }
                default -> throw fail("escape desconocido");
            }
        }
    }
}
