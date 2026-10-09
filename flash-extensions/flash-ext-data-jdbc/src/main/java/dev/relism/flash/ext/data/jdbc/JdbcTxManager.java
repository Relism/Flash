package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public class JdbcTxManager implements TxManager {
    // Per manager, not per class: two databases on one thread never join each other's transaction.
    private final TxResourceKey JDBC_STATUS_KEY = TxResourceKey.of("jdbc.tx.status");
    private final TxResourceKey JDBC_SUSPENDED_KEY = TxResourceKey.of("jdbc.tx.suspended");
    private final TxResourceKey JDBC_READ_KEY = TxResourceKey.of("jdbc.read");

    private final DataSource ds;

    public JdbcTxManager(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
    }

    /** Closes the data source when it is closeable, as a pool is; a plain {@code DataSource} holds nothing to release. */
    @Override
    public void close() {
        if (ds instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close the data source", e);
            }
        }
    }

    @Override
    public TxStatus begin(TxDefinition definition) {
        return switch (definition.propagation()) {
            case REQUIRED -> ResourceRegistry.isBound(JDBC_STATUS_KEY)
                    ? joinExisting(definition)
                    : beginNew(definition);
            case REQUIRES_NEW -> beginNew(definition);
            case SUPPORTS -> ResourceRegistry.isBound(JDBC_STATUS_KEY)
                    ? joinExisting(definition)
                    : outsideTransaction(definition);
            case MANDATORY -> {
                if (!ResourceRegistry.isBound(JDBC_STATUS_KEY))
                    throw new IllegalStateException("MANDATORY: no active transaction");
                yield joinExisting(definition);
            }
            case NOT_SUPPORTED -> {
                JdbcTxStatus suspended = suspendIfNeeded();
                yield noOp(definition, suspended);
            }
        };
    }

    private TxStatus beginNew(TxDefinition definition) {
        Connection conn = null;
        // Anything already registered belongs to an enclosing transaction this one is nested
        // inside (or suspended over) — see ResourceRegistry#fireSynchronizations.
        int synchronizationBaseline = ResourceRegistry.synchronizationCount();
        JdbcTxStatus suspended = suspendIfNeeded();
        boolean bound = false;
        try {
            conn = ds.getConnection();
            conn.setAutoCommit(false);
            if (definition.readOnly()) conn.setReadOnly(true);
            if (definition.isolation() != TransactionIsolation.DEFAULT) {
                conn.setTransactionIsolation(definition.isolation().level());
            }
            JdbcTxStatus status = new JdbcTxStatus(
                    conn,
                    true,
                    definition.readOnly(),
                    suspended,
                    new JdbcTxStatus.RollbackMarker(),
                    synchronizationBaseline
            );
            ResourceRegistry.bind(JDBC_STATUS_KEY, status);
            bound = true;
            return status;
        } catch (SQLException e) {
            silentClose(conn);
            throw new TxException(e);
        } finally {
            if (!bound && suspended != null) {
                ResourceRegistry.unbind(JDBC_SUSPENDED_KEY);
                ResourceRegistry.bind(JDBC_STATUS_KEY, suspended);
            }
        }
    }

    private TxStatus joinExisting(TxDefinition definition) {
        JdbcTxStatus existing = ResourceRegistry.get(JDBC_STATUS_KEY, JdbcTxStatus.class);
        // A read joins whatever it finds; a write cannot run inside a transaction opened read-only.
        if (!definition.readOnly() && existing.isReadOnly()) {
            throw new TxException("Cannot write inside a read-only transaction");
        }
        // Baseline 0 is never read: a joined status isn't a new transaction, so commit()/rollback()
        // hand it straight back to the transaction it joined without firing anything.
        return new JdbcTxStatus(
                existing.connection(),
                false,
                existing.isReadOnly(),
                null,
                existing.rollbackMarker(),
                0
        );
    }

    /**
     * SUPPORTS with no transaction to join: one connection in autocommit for the whole scope, shared
     * by the reads nested in it. No BEGIN and no COMMIT, so a single query costs one round trip.
     */
    private TxStatus outsideTransaction(TxDefinition definition) {
        JdbcTxStatus outer = ResourceRegistry.getOrNull(JDBC_READ_KEY, JdbcTxStatus.class);
        if (outer != null) return new JdbcTxStatus(outer.connection(), false, definition.readOnly(), null, outer.rollbackMarker(), 0);
        Connection conn = null;
        try {
            conn = ds.getConnection();
            if (!conn.getAutoCommit()) conn.setAutoCommit(true);
        } catch (SQLException e) {
            silentClose(conn);
            throw new TxException(e);
        }
        JdbcTxStatus status = new JdbcTxStatus(conn, false, definition.readOnly(), null, new JdbcTxStatus.RollbackMarker(), 0);
        ResourceRegistry.bind(JDBC_READ_KEY, status);
        return status;
    }

    /** Ends the scope {@link #outsideTransaction} opened, when {@code status} is the one that opened it. */
    private boolean endOutsideTransaction(JdbcTxStatus status) {
        if (ResourceRegistry.getOrNull(JDBC_READ_KEY, JdbcTxStatus.class) != status) return false;
        ResourceRegistry.unbind(JDBC_READ_KEY);
        silentClose(status.connection());
        cleanupIfIdle();
        return true;
    }

    private TxStatus noOp(TxDefinition definition, JdbcTxStatus suspended) {
        return new JdbcTxStatus(null, false, definition.readOnly(), suspended, new JdbcTxStatus.RollbackMarker(), 0);
    }

    private JdbcTxStatus suspendIfNeeded() {
        JdbcTxStatus suspended = ResourceRegistry.getOrNull(JDBC_STATUS_KEY, JdbcTxStatus.class);
        if (suspended != null) {
            ResourceRegistry.unbind(JDBC_STATUS_KEY);
            ResourceRegistry.bind(JDBC_SUSPENDED_KEY, suspended);
        }
        return suspended;
    }

    @Override
    public void commit(TxStatus status) {
        JdbcTxStatus s = (JdbcTxStatus) status;
        if (endOutsideTransaction(s)) return;
        if (!s.isNewTransaction()) {
            resumeIfNeeded(s);
            cleanupIfIdle();
            return;
        }
        TxOutcome outcome = null;
        try {
            if (s.isRollbackOnly()) {
                s.connection().rollback();
                outcome = TxOutcome.ROLLED_BACK;
            } else {
                // Still inside the transaction, connection still bound: a beforeCommit callback
                // can write through it and land in this same commit. Throwing from there vetoes
                // the commit — see TxSynchronization.
                ResourceRegistry.fireBeforeCommit(s.isReadOnly(), s.synchronizationBaseline());
                s.connection().commit();
                outcome = TxOutcome.COMMITTED;
            }
        } catch (SQLException e) {
            TxException wrapped = new TxException(e);
            outcome = rollbackAfterFailedCommit(s, wrapped);
            throw wrapped;
        } catch (RuntimeException e) {
            outcome = rollbackAfterFailedCommit(s, e);
            throw e;
        } finally {
            // Unbind the connection before firing, so a callback that opens its own transaction
            // gets a fresh one instead of joining the connection that just committed — and fire
            // before cleanupIfIdle(), whose ResourceRegistry.cleanup() drops the pending list.
            cleanupAndResume(s);
            if (outcome != null) ResourceRegistry.fireSynchronizations(outcome, s.synchronizationBaseline());
            cleanupIfIdle();
        }
    }

    /**
     * Nothing was committed — a vetoing {@code beforeCommit}, or a commit that failed outright —
     * so undo whatever the transaction had done and report {@code ROLLED_BACK} to the callbacks
     * still queued behind it. A failure to roll back is attached to the exception already on its
     * way out rather than replacing it: that one explains what actually went wrong.
     */
    private static TxOutcome rollbackAfterFailedCommit(JdbcTxStatus s, Throwable propagating) {
        try {
            s.connection().rollback();
        } catch (SQLException suppressed) {
            propagating.addSuppressed(suppressed);
        }
        return TxOutcome.ROLLED_BACK;
    }

    @Override
    public void rollback(TxStatus status) {
        JdbcTxStatus s = (JdbcTxStatus) status;
        if (endOutsideTransaction(s)) return;
        if (!s.isNewTransaction()) {
            s.markRollbackOnly();
            resumeIfNeeded(s);
            cleanupIfIdle();
            return;
        }
        try {
            s.connection().rollback();
        } catch (SQLException e) {
            throw new TxException(e);
        } finally {
            // Same order as commit() above, for the same two reasons.
            cleanupAndResume(s);
            ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK, s.synchronizationBaseline());
            cleanupIfIdle();
        }
    }

    private void cleanupAndResume(JdbcTxStatus status) {
        ResourceRegistry.unbind(JDBC_STATUS_KEY);
        try {
            if (status.connection() != null) {
                status.connection().close();
            }
        } catch (SQLException ignored) {
        }
        resumeIfNeeded(status);
    }

    private void cleanupIfIdle() {
        if (!ResourceRegistry.isBound(JDBC_STATUS_KEY) && !ResourceRegistry.isBound(JDBC_SUSPENDED_KEY)
                && !ResourceRegistry.isBound(JDBC_READ_KEY)) {
            ResourceRegistry.cleanup();
        }
    }

    private void resumeIfNeeded(JdbcTxStatus status) {
        JdbcTxStatus suspended = status.suspended();
        if (suspended == null) {
            suspended = ResourceRegistry.getOrNull(JDBC_SUSPENDED_KEY, JdbcTxStatus.class);
        }
        if (suspended != null) {
            ResourceRegistry.unbind(JDBC_SUSPENDED_KEY);
            ResourceRegistry.bind(JDBC_STATUS_KEY, suspended);
        }
    }

    private void silentClose(Connection connection) {
        if (connection == null) {
            return;
        }
        try {
            connection.close();
        } catch (SQLException ignored) {
        }
    }
}
