package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public class JdbcTxManager implements TxManager {
    private static final TxResourceKey JDBC_STATUS_KEY = TxResourceKey.of("jdbc.tx.status");
    private static final TxResourceKey JDBC_SUSPENDED_KEY = TxResourceKey.of("jdbc.tx.suspended");

    private final DataSource ds;

    public JdbcTxManager(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
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
                    : noOp(definition);
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
        if (definition.readOnly() && !existing.isReadOnly()) {
            throw new TxException("Cannot join read-write tx as read-only");
        }
        // Baseline 0 is never read: a joined status isn't a new transaction, so commit()/rollback()
        // hand it straight back to the transaction it joined without firing anything.
        return new JdbcTxStatus(
                existing.connection(),
                false,
                definition.readOnly(),
                null,
                existing.rollbackMarker(),
                0
        );
    }

    private TxStatus noOp(TxDefinition definition) {
        return noOp(definition, null);
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
        if (!ResourceRegistry.isBound(JDBC_STATUS_KEY) && !ResourceRegistry.isBound(JDBC_SUSPENDED_KEY)) {
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
