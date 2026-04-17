package dev.relism.ext.openapi;

import java.util.Locale;

public enum ParameterIn {
    QUERY,
    PATH,
    HEADER,
    COOKIE;

    public String wireValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
