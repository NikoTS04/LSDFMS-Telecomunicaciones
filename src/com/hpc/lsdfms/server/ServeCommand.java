package com.hpc.lsdfms.server;

import com.hpc.lsdfms.core.DatasetService;
import java.io.IOException;
import java.nio.file.Path;

/** Comando {@code serve <dir> [--port N] [--host H] [--web DIR] [--debounce-ms N]}. */
public final class ServeCommand {
    public static final String USAGE = "serve <directorio> [--port N] [--host H] [--web DIR] "
            + "[--debounce-ms N]";

    private ServeCommand() { }

    /** Arranca el servidor y regresa; el hilo de aceptación mantiene vivo el proceso. */
    public static int run(String[] args) {
        try {
            if (args.length < 2) {
                throw new IllegalArgumentException("Falta el directorio del dataset");
            }
            Path directory = Path.of(args[1]);
            String host = "127.0.0.1";
            int port = 8080;
            Path web = Path.of("web");
            long debounce = 5000;
            for (int i = 2; i < args.length; i += 2) {
                if (i + 1 >= args.length) {
                    throw new IllegalArgumentException("Falta el valor de " + args[i]);
                }
                String value = args[i + 1];
                switch (args[i]) {
                    case "--port" -> port = Integer.parseInt(value);
                    case "--host" -> host = value;
                    case "--web" -> web = Path.of(value);
                    case "--debounce-ms" -> debounce = Long.parseLong(value);
                    default -> throw new IllegalArgumentException("Opción desconocida: " + args[i]);
                }
            }
            if (port < 0 || port > 65535) {
                throw new IllegalArgumentException("Puerto fuera de rango: " + port);
            }
            start(directory, host, port, web, debounce);
            return 0;
        } catch (IllegalArgumentException e) {
            System.err.println("Argumentos inválidos: " + e.getMessage() + "\nUso: " + USAGE);
            return 2;
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
            return 1;
        }
    }

    private static void start(Path directory, String host, int port, Path web, long debounce)
            throws IOException {
        DatasetService service = DatasetService.open(directory, debounce);
        Router router = new Router();
        new ApiRoutes(service).register(router);
        StaticFiles files = new StaticFiles(web);
        router.route("GET", "/", files::index);
        router.prefix(StaticFiles.PREFIX, files::file);
        HttpServer server = new HttpServer(host, port, router, 15_000);
        int bound;
        try {
            bound = server.start();
        } catch (IOException | RuntimeException e) {
            service.close();
            throw e;
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            server.close();
            try {
                service.close();
            } catch (IOException e) {
                System.err.println("Error al cerrar el servicio: " + e.getMessage());
            }
        }, "lsdfms-shutdown"));
        System.out.println("SERVIDOR\t" + host + ":" + bound);
        System.out.flush();
    }
}
