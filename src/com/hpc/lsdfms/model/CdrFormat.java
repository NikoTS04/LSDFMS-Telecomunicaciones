package com.hpc.lsdfms.model;

import java.nio.ByteOrder;

/** Contrato binario compartido con scripts/generate_cdr.py. */
public final class CdrFormat {
    public static final ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;
    public static final int HEADER_BYTES = 21;
    public static final int DELETED_OFFSET = 0;
    public static final int LENGTH_OFFSET = 1;
    public static final int PHONE_OFFSET = 5;
    public static final int TIMESTAMP_OFFSET = 13;

    private CdrFormat() { }
}
