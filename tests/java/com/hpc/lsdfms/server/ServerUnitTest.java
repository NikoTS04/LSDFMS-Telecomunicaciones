package com.hpc.lsdfms.server;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** Pruebas unitarias de Json y HttpParser; sale con 1 si alguna comprobación falla. */
public final class ServerUnitTest {
    private static int failures;

    private ServerUnitTest() { }

    public static void main(String[] args) {
        jsonWrite();
        jsonParse();
        parserOk();
        parserErrors();
        if (failures > 0) {
            System.exit(1);
        }
        System.out.println("OK");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures++;
            System.err.println("FALLO: " + message);
        }
    }

    private static void jsonWrite() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("a", 1L);
        map.put("b", "x\"y\\z\n\u00e9\u0001");
        map.put("c", List.of(true, false));
        map.put("d", null);
        check(Json.write(map).equals("{\"a\":1,\"b\":\"x\\\"y\\\\z\\n\u00e9\\u0001\",\"c\":[true,false],\"d\":null}"),
                "escritura: " + Json.write(map));
    }

    private static void jsonParse() {
        var map = Json.parseObject(" {\"s\":\"\\u00e9\\n\",\"n\":-12,\"big\":9223372036854775807,\"t\":true,\"z\":null} ");
        check("\u00e9\n".equals(map.get("s")) && Long.valueOf(-12).equals(map.get("n"))
                && Long.valueOf(Long.MAX_VALUE).equals(map.get("big")) && Boolean.TRUE.equals(map.get("t"))
                && map.containsKey("z") && map.get("z") == null, "lectura válida");
        check(Json.parseObject("{}").isEmpty(), "objeto vacío");
        for (String bad : new String[] {"", "[]", "{", "{\"a\":1.5}", "{\"a\":1e3}", "{\"a\":01}",
                "{\"a\":99999999999999999999}", "{\"a\":{}}", "{\"a\":[1]}", "{\"a\":1,\"a\":2}",
                "{\"a\":1} x", "{\"a\":\"\\q\"}", "{a:1}", "{\"a\":1,}", "{\"a\":\"\n\"}"}) {
            try {
                Json.parseObject(bad);
                check(false, "debía rechazar: " + bad);
            } catch (IllegalArgumentException expected) {
                // correcto
            }
        }
    }

    private static HttpRequest parse(String raw) throws Exception {
        return HttpParser.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.ISO_8859_1)), null);
    }

    private static void parserOk() {
        try {
            HttpRequest r = parse("POST /api/x%20y?col=phone&lo=-1&u=%C3%A9 HTTP/1.1\r\nHost: a\r\n"
                    + "Content-Length: 5\r\n\r\nhello");
            check(r.method().equals("POST") && r.path().equals("/api/x y"), "ruta decodificada");
            check("phone".equals(r.query().get("col")) && "-1".equals(r.query().get("lo"))
                    && "\u00e9".equals(r.query().get("u")), "consulta UTF-8");
            check(new String(r.body(), StandardCharsets.UTF_8).equals("hello"), "cuerpo");
            check(parse("") == null, "conexión vacía");
        } catch (Exception e) {
            check(false, "parserOk: " + e);
        }
    }

    private static void expect(String raw, int status) {
        try {
            parse(raw);
            check(false, "debía fallar con " + status);
        } catch (HttpException e) {
            check(e.status() == status, "esperado " + status + " obtenido " + e.status());
        } catch (Exception e) {
            check(false, "excepción inesperada: " + e);
        }
    }

    private static void parserErrors() {
        expect("GARBAGE\r\n\r\n", 400);
        expect("GET /x HTTP/2.0\r\n\r\n", 400);
        expect("GET /x HTTP/1.1\r\nbad header\r\n\r\n", 400);
        expect("GET /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n", 400);
        expect("GET /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc", 400);
        expect("GET /x?a=%zz HTTP/1.1\r\n\r\n", 400);
        expect("POST /x HTTP/1.1\r\nContent-Length: " + (HttpParser.MAX_BODY_BYTES + 1) + "\r\n\r\n", 413);
        expect("GET /x HTTP/1.1\r\nX: " + "a".repeat(HttpParser.MAX_HEADER_BYTES) + "\r\n\r\n", 431);
        expect("GET /x HTTP/1.1\r\n", 400);
    }
}
