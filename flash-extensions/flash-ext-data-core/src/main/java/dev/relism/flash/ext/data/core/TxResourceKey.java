package dev.relism.flash.ext.data.core;

import java.util.Objects;

public final class TxResourceKey {
    private final String name;

    private TxResourceKey(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public static TxResourceKey of(String name) {
        return new TxResourceKey(name);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TxResourceKey that)) return false;
        return name.equals(that.name);
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}
