package com.hpc.lsdfms.model;

import java.nio.ByteBuffer;

/** Utilidades de serialización; no instancia un objeto por entrada del índice. */
public final class IndexEntry {
    public static final int BYTES = 20;
    public static final int PHONE_OFFSET = 0;
    public static final int FILE_ID_OFFSET = 8;
    public static final int BYTE_OFFSET = 12;

    private IndexEntry() { }

    /** Agrega una entrada y avanza la posición del buffer en 20 bytes. */
    public static void write(ByteBuffer buffer, long phoneNumber, int fileId, long byteOffset) {
        buffer.order(CdrFormat.BYTE_ORDER)
                .putLong(phoneNumber).putInt(fileId).putLong(byteOffset);
    }
}
