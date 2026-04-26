package dev.relism.flash.ext.openapi;

import java.util.Locale;

public enum SchemaType {
    STRING,
    INTEGER,
    NUMBER,
    BOOLEAN,
    OBJECT,
    ARRAY;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
