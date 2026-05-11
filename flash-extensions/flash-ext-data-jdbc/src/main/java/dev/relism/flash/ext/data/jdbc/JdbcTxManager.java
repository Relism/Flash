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
                    new JdbcTxStatus.RollbackMarker()
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
        return new JdbcTxStatus(
                existing.connection(),
                false,
                definition.readOnly(),
                null,
                existing.rollbackMarker()
        );
    }

    private TxStatus noOp(TxDefinition definition) {
        return noOp(definition, null);
    }

    private TxStatus noOp(TxDefinition definition, JdbcTxStatus suspended) {
        return new JdbcTxStatus(null, false, definition.readOnly(), suspended, new JdbcTxStatus.RollbackMarker());
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
        try {
            if (s.isRollbackOnly()) {
                s.connection().rollback();
                ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK);
                return;
            }
            s.connection().commit();
            ResourceRegistry.fireSynchronizations(TxOutcome.COMMITTED);
        } catch (SQLException e) {
            throw new TxException(e);
        } finally {
            cleanupAndResume(s);
            cleanupIfIdle();
        }
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
            ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK);
        } catch (SQLException e) {
            throw new TxException(e);
        } finally {
            cleanupAndResume(s);
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
