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

    /**
     * How many synchronizations are registered right now — captured by a transaction manager when
     * it opens a new transaction, and handed back to {@link #fireSynchronizations} on completion
     * so that transaction only fires its own. See there for why that matters.
     */
    public static int synchronizationCount() {
        return SYNCHRONIZATIONS.get().size();
    }

    /**
     * Fires (and removes) the synchronizations registered from {@code fromIndex} onward — the ones
     * belonging to the transaction now completing. Everything before that index was registered by
     * an enclosing transaction that is merely <em>suspended</em>, not finished: a REQUIRES_NEW
     * inner transaction sets its own baseline, so committing it no longer drags the outer's
     * pending callbacks along — which fired them early, and with the inner transaction's outcome,
     * for an outer transaction that might still roll back.
     */
    public static void fireSynchronizations(TxOutcome outcome, int fromIndex) {
        List<TxSynchronization> pending = SYNCHRONIZATIONS.get();
        if (fromIndex >= pending.size()) {
            return;
        }
        List<TxSynchronization> syncs = List.copyOf(pending.subList(fromIndex, pending.size()));
        pending.subList(fromIndex, pending.size()).clear();
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
