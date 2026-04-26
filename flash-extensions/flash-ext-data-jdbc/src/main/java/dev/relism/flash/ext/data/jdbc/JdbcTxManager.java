package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

public class JdbcTxManager implements TxManager {
    private static final TxResourceKey JDBC_STATUS_KEY = TxResourceKey.of("jdbc.tx.status");

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
            case MANDATORY -> {
                if (!ResourceRegistry.isBound(JDBC_STATUS_KEY))
                    throw new IllegalStateException("MANDATORY: no active transaction");
                yield joinExisting(definition);
            }
            case NOT_SUPPORTED -> throw new UnsupportedOperationException("NOT_SUPPORTED is not implemented");
        };
    }

    private TxStatus beginNew(TxDefinition definition) {
        try {
            JdbcTxStatus suspended = ResourceRegistry.getOrNull(JDBC_STATUS_KEY, JdbcTxStatus.class);
            if (suspended != null) {
                ResourceRegistry.unbind(JDBC_STATUS_KEY);
            }
            Connection conn = ds.getConnection();
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
            return status;
        } catch (SQLException e) {
            throw new TxException(e);
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

    @Override
    public void commit(TxStatus status) {
        JdbcTxStatus s = (JdbcTxStatus) status;
        if (!s.isNewTransaction()) {
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
        }
    }

    @Override
    public void rollback(TxStatus status) {
        JdbcTxStatus s = (JdbcTxStatus) status;
        if (!s.isNewTransaction()) {
            s.markRollbackOnly();
            return;
        }
        try {
            s.connection().rollback();
            ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK);
        } catch (SQLException e) {
            throw new TxException(e);
        } finally {
            cleanupAndResume(s);
        }
    }

    private void cleanupAndResume(JdbcTxStatus status) {
        ResourceRegistry.unbind(JDBC_STATUS_KEY);
        try {
            status.connection().close();
        } catch (SQLException ignored) {
        }
        JdbcTxStatus suspended = status.suspended();
        if (suspended != null) {
            ResourceRegistry.bind(JDBC_STATUS_KEY, suspended);
        }
    }
}
