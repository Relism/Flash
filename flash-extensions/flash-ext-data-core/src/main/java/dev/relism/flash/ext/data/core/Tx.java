package dev.relism.flash.ext.data.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class Tx {
    private static final ThreadLocal<Deque<TxStatus>> STATUS_STACK =
            ThreadLocal.withInitial(ArrayDeque::new);
    private final TxManager manager;

    public Tx(TxManager txManager) {
        this.manager = Objects.requireNonNull(txManager);
    }

    public void run(TxRunnable work) {
        run(TxDefinition.DEFAULTS, work);
    }

    public void run(TxDefinition definition, TxRunnable work) {
        call(definition, () -> {
            work.run();
            return null;
        });
    }

    public <T> T call(TxCallable<T> work) {
        return call(TxDefinition.DEFAULTS, work);
    }

    public <T> T call(TxDefinition definition, TxCallable<T> work) {
        TxStatus status = manager.begin(definition);
        pushStatus(status);
        try {
            T result = work.call();
            if (status.isRollbackOnly()) {
                manager.rollback(status);
            } else {
                manager.commit(status);
            }
            return result;
        } catch (Exception e) {
            silentRollback(status);
            throw (e instanceof TxException txException) ? txException : new TxException(e);
        } catch (Throwable t) {
            silentRollback(status);
            throw sneakyThrow(t);
        } finally {
            popStatus();
            if (STATUS_STACK.get().isEmpty()) {
                STATUS_STACK.remove();
            }
        }
    }

    public boolean isActive() {
        return !STATUS_STACK.get().isEmpty();
    }

    public void setRollbackOnly() {
        currentStatus().markRollbackOnly();
    }

    public <R> R resource(Class<R> type) {
        return currentStatus().resource(type);
    }

    public TxDefinition requiresNew() {
        return TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW);
    }

    public TxDefinition readOnly() {
        return TxDefinition.DEFAULTS.asReadOnly();
    }

    private TxStatus currentStatus() {
        TxStatus status = STATUS_STACK.get().peek();
        if (status == null) {
            throw new IllegalStateException("No active transaction");
        }
        return status;
    }

    private void pushStatus(TxStatus status) {
        STATUS_STACK.get().push(status);
    }

    private void popStatus() {
        Deque<TxStatus> stack = STATUS_STACK.get();
        if (!stack.isEmpty()) {
            stack.pop();
        }
    }

    private void silentRollback(TxStatus status) {
        try {
            manager.rollback(status);
        } catch (Exception ignored) {
        }
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
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
