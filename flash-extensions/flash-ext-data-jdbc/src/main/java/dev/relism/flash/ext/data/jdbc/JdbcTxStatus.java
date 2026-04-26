package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.TxStatus;

import java.sql.Connection;
import java.util.Objects;

class JdbcTxStatus implements TxStatus {
    static final class RollbackMarker {
        boolean rollbackOnly;
    }

    private final Connection connection;
    private final boolean newTransaction;
    private final boolean readOnly;
    private final JdbcTxStatus suspended;
    private final RollbackMarker rollbackMarker;

    JdbcTxStatus(
            Connection connection,
            boolean newTransaction,
            boolean readOnly,
            JdbcTxStatus suspended,
            RollbackMarker rollbackMarker
    ) {
        this.connection = Objects.requireNonNull(connection);
        this.newTransaction = newTransaction;
        this.readOnly = readOnly;
        this.suspended = suspended;
        this.rollbackMarker = rollbackMarker;
    }

    @Override public boolean isNewTransaction() { return newTransaction; }
    @Override public boolean isReadOnly() { return readOnly; }
    @Override public boolean isRollbackOnly() { return rollbackMarker.rollbackOnly; }
    @Override public void markRollbackOnly() { rollbackMarker.rollbackOnly = true; }

    @Override
    public <R> R resource(Class<R> type) {
        return type.cast(connection);
    }

    Connection connection() { return connection; }
    JdbcTxStatus suspended() { return suspended; }
    RollbackMarker rollbackMarker() { return rollbackMarker; }
}
