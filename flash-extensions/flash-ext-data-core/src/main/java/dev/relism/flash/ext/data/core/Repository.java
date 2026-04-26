package dev.relism.flash.ext.data.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Base repository. Subclasses only extend this — never HibernateRepository
 * or JdbcRepository directly. The concrete backing is transparent.
 *
 * Every method auto-wraps in REQUIRED transaction — safe to call with or
 * without an active transaction on the thread.
 */
public abstract class Repository<T, ID> {

    private final TxDefinition required = TxDefinition.DEFAULTS
            .withPropagation(TransactionPropagation.REQUIRED);

    // ── CRUD ──────────────────────────────────────────────────────────────────

    public Optional<T> findById(ID id) {
        return tx(() -> doFindById(id));
    }

    public List<T> findAll() {
        return tx(this::doFindAll);
    }

    public List<T> findAll(int page, int size) {
        return tx(() -> doFindAll(page, size));
    }

    public List<T> findAll(Sort sort) {
        return tx(() -> doFindAll(sort));
    }

    public List<T> findAll(int page, int size, Sort sort) {
        return tx(() -> doFindAll(page, size, sort));
    }

    public Page<T> findPage(int page, int size) {
        return tx(() -> doFindPage(page, size));
    }

    public Page<T> findPage(int page, int size, Sort sort) {
        return tx(() -> doFindPage(page, size, sort));
    }

    public T save(T entity) {
        return tx(() -> doSave(entity));
    }

    public List<T> saveAll(Iterable<T> entities) {
        return tx(() -> {
            List<T> saved = new ArrayList<>();
            for (T e : entities) saved.add(doSave(e));
            return saved;
        });
    }

    public T update(T entity) {
        return tx(() -> doUpdate(entity));
    }

    public void delete(T entity) {
        tx(() -> { doDelete(entity); return null; });
    }

    public void deleteById(ID id) {
        tx(() -> { doDeleteById(id); return null; });
    }

    public void deleteAll(Iterable<T> entities) {
        tx(() -> { entities.forEach(this::doDelete); return null; });
    }

    public boolean existsById(ID id) {
        return tx(() -> doExistsById(id));
    }

    public long count() {
        return tx(this::doCount);
    }

    // ── Auto-wrap helper ──────────────────────────────────────────────────────

    /**
     * Ensures the work runs inside a transaction.
     * If one is already active (caller annotated @Transactional or inside Tx.run)
     * it joins it — no new connection opened.
     * If none is active it opens one, commits, and closes it transparently.
     */
    protected final <R> R tx(Tx.TxCallable<R> work) {
        return Tx.call(required, work);
    }

    // ── Abstract — implemented by HibernateRepository / JdbcRepository ────────

    protected abstract Optional<T> doFindById(ID id);
    protected abstract List<T>     doFindAll();
    protected abstract List<T>     doFindAll(int page, int size);
    protected abstract List<T>     doFindAll(Sort sort);
    protected abstract List<T>     doFindAll(int page, int size, Sort sort);
    protected abstract Page<T>     doFindPage(int page, int size);
    protected abstract Page<T>     doFindPage(int page, int size, Sort sort);
    protected abstract T           doSave(T entity);
    protected abstract T           doUpdate(T entity);
    protected abstract void        doDelete(T entity);
    protected abstract void        doDeleteById(ID id);
    protected abstract boolean     doExistsById(ID id);
    protected abstract long        doCount();
}