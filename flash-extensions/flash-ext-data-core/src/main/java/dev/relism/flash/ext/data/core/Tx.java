package dev.relism.flash.ext.data.core;

import java.util.ArrayDeque;
import java.util.Deque;

public final class Tx {
    private static final ThreadLocal<Deque<TxStatus>> STATUS_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);
    private static volatile TxManager manager;

    private Tx() {}

    public static void init(TxManager txManager) {
        if (manager != null) {
            throw new IllegalStateException("TxManager already initialized");
        }
        manager = txManager;
    }

    public static void run(TxRunnable work) {
        run(TxDefinition.DEFAULTS, work);
    }

    public static void run(TxDefinition definition, TxRunnable work) {
        call(definition, () -> {
            work.run();
            return null;
        });
    }

    public static <T> T call(TxCallable<T> work) {
        return call(TxDefinition.DEFAULTS, work);
    }

    public static <T> T call(TxDefinition definition, TxCallable<T> work) {
        TxStatus status = manager().begin(definition);
        pushStatus(status);
        try {
            T result = work.call();
            if (status.isRollbackOnly()) {
                manager().rollback(status);
            } else {
                manager().commit(status);
            }
            return result;
        } catch (Exception e) {
            manager().rollback(status);
            throw (e instanceof TxException txException) ? txException : new TxException(e);
        } finally {
            popStatus();
        }
    }

    public static boolean isActive() {
        return !STATUS_STACK.get().isEmpty();
    }

    public static void setRollbackOnly() {
        currentStatus().markRollbackOnly();
    }

    public static <R> R resource(Class<R> type) {
        return currentStatus().resource(type);
    }

    public static TxDefinition requiresNew() {
        return TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW);
    }

    public static TxDefinition readOnly() {
        return TxDefinition.DEFAULTS.asReadOnly();
    }

    private static TxManager manager() {
        if (manager == null) {
            throw new IllegalStateException("No TxManager installed");
        }
        return manager;
    }

    private static TxStatus currentStatus() {
        TxStatus status = STATUS_STACK.get().peek();
        if (status == null) {
            throw new IllegalStateException("No active transaction");
        }
        return status;
    }

    private static void pushStatus(TxStatus status) {
        STATUS_STACK.get().push(status);
    }

    private static void popStatus() {
        Deque<TxStatus> stack = STATUS_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    @FunctionalInterface
    public interface TxRunnable {
        void run();
    }

    @FunctionalInterface
    public interface TxCallable<T> {
        T call() throws Exception;
    }
}
