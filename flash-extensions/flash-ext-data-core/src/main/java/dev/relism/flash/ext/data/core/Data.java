package dev.relism.flash.ext.data.core;

import java.util.Objects;
import java.io.Serializable;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Application-facing data gateway. Repositories are created once per entity type and are safe to
 * share: transaction/session state stays in {@link Tx}, never in the repository instance.
 */
public final class Data {
    private final Tx tx;
    private final RepositoryFactory repositories;
    private final ConcurrentHashMap<Class<?>, Repository<?, ?>> cache = new ConcurrentHashMap<>();

    public Data(Tx tx, RepositoryFactory repositories) {
        this.tx = Objects.requireNonNull(tx, "tx");
        this.repositories = Objects.requireNonNull(repositories, "repositories");
    }

    public Tx tx() { return tx; }

    @SuppressWarnings("unchecked")
    public <T, ID extends Serializable> Repository<T, ID> repository(Class<T> type) {
        Objects.requireNonNull(type, "type");
        return (Repository<T, ID>) cache.computeIfAbsent(type, key -> repositories.create(tx, type));
    }

    /** See {@link Tx#read}: the caller's transaction if there is one, otherwise none. */
    public void read(Tx.TxRunnable work) {
        tx.read(() -> {
            work.run();
            return null;
        });
    }

    public <T> T read(Tx.TxCallable<T> work) { return tx.read(work); }
    public void write(Tx.TxRunnable work) { tx.run(work); }
    public <T> T write(Tx.TxCallable<T> work) { return tx.call(work); }
    /** Transitional low-level access for infrastructure that needs an explicit definition. */

    /** Registers work that runs only after the enclosing write transaction commits. */
    public void afterCommit(Runnable work) {
        if (!tx.isActive()) throw new IllegalStateException("afterCommit requires an active transaction");
        ResourceRegistry.addSynchronization(new TxSynchronization() {
            @Override public void afterCommit() { work.run(); }
        });
    }
}
