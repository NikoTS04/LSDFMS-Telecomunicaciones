package com.hpc.lsdfms.server;

/** Error HTTP con código de estado explícito; el router lo traduce a una respuesta JSON. */
public final class HttpException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int status;

    public HttpException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
