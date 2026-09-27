package dev.relism.flash.ext.data.core;

import java.util.Objects;

/**
 * A slot in the thread's transaction resources. Equal only to itself: a manager holds its own keys,
 * so two managers on one thread never see each other's transaction.
 */
public final class TxResourceKey {
    private final String name;

    private TxResourceKey(String name) {
        this.name = Objects.requireNonNull(name);
    }

    public static TxResourceKey of(String name) {
        return new TxResourceKey(name);
    }

    @Override
    public String toString() {
        return name;
    }
}
