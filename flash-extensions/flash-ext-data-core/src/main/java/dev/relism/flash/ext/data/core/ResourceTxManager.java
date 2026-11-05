package dev.relism.flash.ext.data.core;

/**
 * The propagation rules of a {@link TxManager} over one resource per transaction, a connection or a
 * session, bound to the current thread. A backend says how its resource is acquired, begun,
 * committed, rolled back and released; joining, suspending, resuming and firing the
 * synchronizations are done here, the same for every backend.
 *
 * <p>A failure of a hook leaves as it was thrown when unchecked, wrapped in {@link TxException} when
 * checked.
 */
public abstract class ResourceTxManager<R> implements TxManager {

    // Per manager, not per class: two databases on one thread never join each other's transaction.
    private final TxResourceKey current = TxResourceKey.of(getClass().getSimpleName() + ".current");
    private final TxResourceKey suspended = TxResourceKey.of(getClass().getSimpleName() + ".suspended");
    private final TxResourceKey reads = TxResourceKey.of(getClass().getSimpleName() + ".reads");

    /** A new resource, outside any transaction. */
    protected abstract R acquire() throws Exception;

    /** Starts a transaction on {@code resource}, read-only and isolated as {@code definition} says. */
    protected abstract void begin(R resource, TxDefinition definition) throws Exception;

    protected abstract void commitResource(R resource) throws Exception;

    protected abstract void rollbackResource(R resource) throws Exception;

    protected abstract void release(R resource) throws Exception;

    /**
     * For {@code SUPPORTS} with no transaction to join: a resource that reads outside any, shared by
     * the reads nested in it, or {@code null} to open a transaction instead.
     */
    protected R acquireForReads() throws Exception {
        return null;
    }

    /** Releases {@code resource}, ignoring a failure: for a resource already given up on. */
    protected final void discard(R resource) {
        if (resource == null) return;
        try {
            release(resource);
        } catch (Exception ignored) {
        }
    }

    /**
     * Opens or joins a scope according to {@code definition}.
     *
     * @throws IllegalStateException if propagation is mandatory and no transaction is active
     * @throws TxException if a write attempts to join a read-only transaction, or a resource hook
     *         throws a checked exception
     */
    @Override
    public TxStatus begin(TxDefinition definition) {
        Status<R> joined = bound(current);
        return switch (definition.propagation()) {
            case REQUIRED -> joined != null ? join(joined, definition) : beginNew(definition);
            case REQUIRES_NEW -> beginNew(definition);
            case SUPPORTS -> joined != null ? join(joined, definition) : outside(definition);
            case MANDATORY -> {
                if (joined == null) throw new IllegalStateException("MANDATORY: no active transaction");
                yield join(joined, definition);
            }
            case NOT_SUPPORTED -> new Status<>(null, false, definition.readOnly(), suspend(), new boolean[1], 0);
        };
    }

    private TxStatus beginNew(TxDefinition definition) {
        // What is registered already belongs to an enclosing transaction: see ResourceRegistry#fireSynchronizations.
        int baseline = ResourceRegistry.synchronizationCount();
        Status<R> outer = suspend();
        R resource = null;
        boolean begun = false;
        try {
            resource = acquire();
            begin(resource, definition);
            begun = true;
        } catch (Exception failure) {
            throw unchecked(failure);
        } finally {
            if (!begun) {   // an Error too: the outer transaction comes back, the resource goes
                resume(outer);
                discard(resource);
            }
        }
        Status<R> status = new Status<>(resource, true, definition.readOnly(), outer, new boolean[1], baseline);
        ResourceRegistry.bind(current, status);
        return status;
    }

    private Status<R> join(Status<R> existing, TxDefinition definition) {
        // A read joins whatever it finds; a write cannot run inside a transaction opened read-only.
        if (!definition.readOnly() && existing.readOnly)
            throw new TxException("Cannot write inside a read-only transaction");
        return new Status<>(existing.resource, false, existing.readOnly, null, existing.rollbackOnly, 0);
    }

    /** One resource outside any transaction for the whole scope, shared by the reads nested in it. */
    private TxStatus outside(TxDefinition definition) {
        Status<R> scope = bound(reads);
        if (scope != null) return new Status<>(scope.resource, false, definition.readOnly(), null, scope.rollbackOnly, 0);
        R resource;
        try {
            resource = acquireForReads();
        } catch (Exception failure) {
            throw unchecked(failure);
        }
        if (resource == null) return beginNew(definition);
        Status<R> status = new Status<>(resource, false, definition.readOnly(), null, new boolean[1], 0);
        ResourceRegistry.bind(reads, status);
        return status;
    }

    private Status<R> suspend() {
        Status<R> outer = bound(current);
        if (outer != null) {
            ResourceRegistry.unbind(current);
            ResourceRegistry.bind(suspended, outer);
        }
        return outer;
    }

    /**
     * Commits a new transaction, or rolls it back if marked rollback-only. An exception before or
     * during commit triggers a rollback attempt; a rollback exception is suppressed on the original.
     * Completion callbacks can also throw. Completing a joined scope leaves the transaction open.
     */
    @Override
    public void commit(TxStatus status) {
        Status<R> s = cast(status);
        if (endReads(s)) return;
        if (!s.newTransaction) {
            resume(s.outer);
            ResourceRegistry.cleanupIfIdle();
            return;
        }
        TxOutcome outcome = null;
        try {
            if (s.isRollbackOnly()) {
                rollbackResource(s.resource);
                outcome = TxOutcome.ROLLED_BACK;
            } else {
                // Still inside the transaction, its resource still bound: a beforeCommit callback can
                // write through it, or veto the commit by throwing. See TxSynchronization.
                ResourceRegistry.fireBeforeCommit(s.readOnly, s.baseline);
                commitResource(s.resource);
                outcome = TxOutcome.COMMITTED;
            }
        } catch (Exception | Error failure) {
            // Nothing was committed: undo the work, and tell the callbacks it rolled back. A failure
            // to roll back is attached to the exception that explains what went wrong.
            try {
                rollbackResource(s.resource);
            } catch (Exception suppressed) {
                failure.addSuppressed(suppressed);
            }
            outcome = TxOutcome.ROLLED_BACK;
            if (failure instanceof Error error) throw error;
            throw unchecked((Exception) failure);
        } finally {
            complete(s, outcome);
        }
    }

    /** Rolls back a new transaction; a joined scope marks its transaction rollback-only. */
    @Override
    public void rollback(TxStatus status) {
        Status<R> s = cast(status);
        if (endReads(s)) return;
        if (!s.newTransaction) {
            s.markRollbackOnly();
            resume(s.outer);
            ResourceRegistry.cleanupIfIdle();
            return;
        }
        try {
            rollbackResource(s.resource);
        } catch (Exception failure) {
            throw unchecked(failure);
        } finally {
            complete(s, TxOutcome.ROLLED_BACK);
        }
    }

    /**
     * Fires the callbacks with this transaction unbound and the one it suspended still suspended, so
     * a callback that opens a transaction gets a new one, joining neither; then resumes, and fires
     * before {@link ResourceRegistry#cleanupIfIdle}, which drops the pending list.
     */
    private void complete(Status<R> s, TxOutcome outcome) {
        ResourceRegistry.unbind(current);
        discard(s.resource);
        try {
            if (outcome != null) ResourceRegistry.fireSynchronizations(outcome, s.baseline);
        } finally {
            resume(s.outer);
            ResourceRegistry.cleanupIfIdle();
        }
    }

    /** Ends the scope {@link #outside} opened, when {@code s} is the status that opened it. */
    private boolean endReads(Status<R> s) {
        if (bound(reads) != s) return false;
        ResourceRegistry.unbind(reads);
        discard(s.resource);
        ResourceRegistry.cleanupIfIdle();
        return true;
    }

    private void resume(Status<R> outer) {
        if (outer == null) return;
        ResourceRegistry.unbind(suspended);
        ResourceRegistry.bind(current, outer);
    }

    @SuppressWarnings("unchecked")
    private Status<R> bound(TxResourceKey key) {
        return ResourceRegistry.getOrNull(key, Status.class);
    }

    @SuppressWarnings("unchecked")
    private Status<R> cast(TxStatus status) {
        return (Status<R>) status;
    }

    private static RuntimeException unchecked(Exception failure) {
        return failure instanceof RuntimeException runtime ? runtime : new TxException(failure);
    }

    /**
     * One use of a resource. A joined status shares its transaction's resource and rollback flag and
     * suspends nothing; {@code baseline} is where the transaction's own synchronizations start, read
     * only for a new one.
     */
    private static final class Status<R> implements TxStatus {
        final R resource;
        final boolean newTransaction;
        final boolean readOnly;
        final Status<R> outer;
        final boolean[] rollbackOnly;
        final int baseline;

        Status(R resource, boolean newTransaction, boolean readOnly, Status<R> outer, boolean[] rollbackOnly, int baseline) {
            this.resource = resource;
            this.newTransaction = newTransaction;
            this.readOnly = readOnly;
            this.outer = outer;
            this.rollbackOnly = rollbackOnly;
            this.baseline = baseline;
        }

        @Override public boolean isNewTransaction() { return newTransaction; }
        @Override public boolean isReadOnly() { return readOnly; }
        @Override public boolean isRollbackOnly() { return rollbackOnly[0]; }
        @Override public void markRollbackOnly() { rollbackOnly[0] = true; }

        @Override
        public <T> T resource(Class<T> type) {
            if (resource == null) throw new IllegalStateException("No resource bound to this transaction status");
            return type.cast(resource);
        }
    }
}
