package dev.relism.flash.ext.data.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Runs work in a transaction of one {@link TxManager}: commits when it returns, rolls back when it
 * throws or was marked rollback-only. Its nesting is its own, per thread: two {@code Tx} over two
 * databases never see each other's transaction.
 *
 * <p>A {@link RuntimeException} leaves exactly as it was thrown — an {@code HttpException} keeps its
 * status — and a checked exception is wrapped in {@link TxException}.
 */
public final class Tx {

    private final ThreadLocal<Deque<TxStatus>> stack = ThreadLocal.withInitial(ArrayDeque::new);
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
        Deque<TxStatus> statuses = stack.get();
        statuses.push(status);
        try {
            T result = work.call();
            if (status.isRollbackOnly()) manager.rollback(status);
            else manager.commit(status);
            return result;
        } catch (RuntimeException | Error failure) {
            silentRollback(status);
            throw failure;
        } catch (Exception checked) {
            silentRollback(status);
            throw new TxException(checked);
        } finally {
            statuses.pop();
            if (statuses.isEmpty()) stack.remove();
        }
    }

    public boolean isActive() {
        return !stack.get().isEmpty();
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

    /**
     * Reads inside the caller's transaction when there is one, and in none otherwise: the reads
     * nested in {@code work} share one connection, without a BEGIN and a COMMIT around them. Use
     * {@code call(readOnly(), work)} when the reads must see one snapshot.
     */
    public <T> T read(TxCallable<T> work) {
        return call(READ, work);
    }

    private static final TxDefinition READ =
            TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.SUPPORTS).asReadOnly();

    private TxStatus currentStatus() {
        TxStatus status = stack.get().peek();
        if (status == null) throw new IllegalStateException("No active transaction");
        return status;
    }

    private void silentRollback(TxStatus status) {
        try {
            manager.rollback(status);
        } catch (Exception ignored) {
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
