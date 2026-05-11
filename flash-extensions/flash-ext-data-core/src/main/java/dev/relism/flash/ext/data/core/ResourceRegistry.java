package dev.relism.flash.ext.data.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ResourceRegistry {
    private static final ThreadLocal<Map<TxResourceKey, Object>> RESOURCES =
            ThreadLocal.withInitial(HashMap::new);
    private static final ThreadLocal<List<TxSynchronization>> SYNCHRONIZATIONS =
            ThreadLocal.withInitial(ArrayList::new);

    private ResourceRegistry() {}

    public static void bind(TxResourceKey key, Object value) {
        RESOURCES.get().put(key, Objects.requireNonNull(value));
    }

    public static boolean isBound(TxResourceKey key) {
        return RESOURCES.get().containsKey(key);
    }

    public static void unbind(TxResourceKey key) {
        RESOURCES.get().remove(key);
    }

    public static void clear() {
        RESOURCES.get().clear();
        SYNCHRONIZATIONS.get().clear();
    }

    public static void cleanup() {
        RESOURCES.remove();
        SYNCHRONIZATIONS.remove();
    }

    public static <R> R get(TxResourceKey key, Class<R> type) {
        Object value = RESOURCES.get().get(key);
        if (value == null) {
            throw new IllegalStateException("No resource bound for key: " + key);
        }
        return type.cast(value);
    }

    public static <R> R getOrNull(TxResourceKey key, Class<R> type) {
        Object value = RESOURCES.get().get(key);
        return value == null ? null : type.cast(value);
    }

    public static void addSynchronization(TxSynchronization sync) {
        SYNCHRONIZATIONS.get().add(Objects.requireNonNull(sync));
    }

    public static void fireSynchronizations(TxOutcome outcome) {
        List<TxSynchronization> syncs = List.copyOf(SYNCHRONIZATIONS.get());
        SYNCHRONIZATIONS.get().clear();
        for (TxSynchronization sync : syncs) {
            if (outcome == TxOutcome.COMMITTED) {
                sync.afterCommit();
            } else {
                sync.afterRollback();
            }
            sync.afterCompletion(outcome);
        }
    }
}
