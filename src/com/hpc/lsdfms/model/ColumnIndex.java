package com.hpc.lsdfms.model;

import java.util.Locale;

/** Columnas indexables; cada una tiene su archivo con entradas de 20 bytes (clave, fileId, offset). */
public enum ColumnIndex {
    PHONE("phone", "index_phone.idx"),
    TIMESTAMP("timestamp", "index_timestamp.idx"),
    SIZE("size", "index_size.idx");

    private final String columnName;
    private final String fileName;

    ColumnIndex(String columnName, String fileName) {
        this.columnName = columnName;
        this.fileName = fileName;
    }

    public String columnName() {
        return columnName;
    }

    public String fileName() {
        return fileName;
    }

    /** Interpreta phone, timestamp o size sin distinguir mayúsculas. */
    public static ColumnIndex parse(String name) {
        String normalized = name.toLowerCase(Locale.ROOT);
        for (ColumnIndex column : values()) {
            if (column.columnName.equals(normalized)) {
                return column;
            }
        }
        throw new IllegalArgumentException("Columna inválida (use phone, timestamp o size): " + name);
    }
}
