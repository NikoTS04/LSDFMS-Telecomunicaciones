package com.hpc.lsdfms.server;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Servidor HTTP/1.1 sobre {@link ServerSocket}: un hilo de aceptación y un hilo explícito por
 * conexión (una petición por conexión, {@code Connection: close}). El número de conexiones
 * simultáneas se acota con un contador protegido por monitor.
 */
public final class HttpServer implements AutoCloseable {
    public static final int MAX_CONNECTIONS = 128;
    private static final int DRAIN_LIMIT = 4 * 1024 * 1024;

    private final String host;
    private final int requestedPort;
    private final Router router;
    private final int readTimeoutMs;
    private final Object lock = new Object();
    private final Set<Socket> active = new HashSet<>();
    private ServerSocket serverSocket;
    private Thread acceptor;
    private boolean closed;

    public HttpServer(String host, int port, Router router, int readTimeoutMs) {
        this.host = host;
        this.requestedPort = port;
        this.router = router;
        this.readTimeoutMs = readTimeoutMs;
    }

    /** Enlaza el socket y arranca el hilo de aceptación; devuelve el puerto real. */
    public int start() throws IOException {
        ServerSocket socket = new ServerSocket();
        try {
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(InetAddress.getByName(host), requestedPort), 128);
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
        serverSocket = socket;
        acceptor = new Thread(this::acceptLoop, "lsdfms-http-accept");
        acceptor.start();
        return socket.getLocalPort();
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public String host() {
        return host;
    }

    private void acceptLoop() {
        while (true) {
            Socket client;
            try {
                client = serverSocket.accept();
            } catch (IOException e) {
                return; // socket cerrado: fin ordenado
            }
            synchronized (lock) {
                while (!closed && active.size() >= MAX_CONNECTIONS) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        closeQuietly(client);
                        return;
                    }
                }
                if (closed) {
                    closeQuietly(client);
                    return;
                }
                active.add(client);
            }
            Thread worker = new Thread(() -> serve(client), "lsdfms-http-conn");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void serve(Socket client) {
        try {
            client.setSoTimeout(readTimeoutMs);
            client.setTcpNoDelay(true);
            var in = new BufferedInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();
            HttpResponse response;
            boolean unreadBody = false;
            try {
                HttpRequest request = HttpParser.parse(in, out);
                if (request == null) {
                    return;
                }
                response = router.dispatch(request);
            } catch (HttpException e) {
                response = HttpResponse.error(e.status(), e.getMessage());
                unreadBody = true;
            }
            write(out, response);
            if (unreadBody) {
                drain(client, in);
            }
        } catch (SocketTimeoutException e) {
            tryWrite(client, HttpResponse.error(408, "Tiempo de espera agotado"));
        } catch (IOException e) {
            // el cliente se fue: nada que responder
        } finally {
            closeQuietly(client);
            synchronized (lock) {
                active.remove(client);
                lock.notifyAll();
            }
        }
    }

    private static void write(OutputStream out, HttpResponse response) throws IOException {
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(response.status()).append(' ')
                .append(HttpResponse.reason(response.status())).append("\r\nContent-Type: ")
                .append(response.contentType()).append("\r\nContent-Length: ")
                .append(response.body().length).append("\r\nConnection: close\r\n");
        for (Map.Entry<String, String> h : response.headers().entrySet()) {
            head.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
        }
        head.append("\r\n");
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(response.body());
        out.flush();
    }

    private static void tryWrite(Socket client, HttpResponse response) {
        try {
            write(client.getOutputStream(), response);
        } catch (IOException e) {
            // sin respuesta posible
        }
    }

    /** Descarta el resto de la petición para que el cliente alcance a leer el error (evita RST). */
    private static void drain(Socket client, BufferedInputStream in) {
        try {
            client.shutdownOutput();
            byte[] buffer = new byte[8192];
            int total = 0;
            int n;
            while (total < DRAIN_LIMIT && (n = in.read(buffer)) >= 0) {
                total += n;
            }
        } catch (IOException e) {
            // tiempo agotado o conexión cerrada: se cierra igualmente
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // nada que hacer
        }
    }

    /** Deja de aceptar, cierra las conexiones en curso y espera a que terminen. Idempotente. */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            lock.notifyAll();
        }
        try {
            serverSocket.close();
        } catch (IOException e) {
            // ya cerrado
        }
        synchronized (lock) {
            for (Socket socket : active) {
                closeQuietly(socket);
            }
            long deadline = System.currentTimeMillis() + 5000;
            while (!active.isEmpty()) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    break;
                }
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        try {
            acceptor.join(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
