package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.TxStatus;

import java.sql.Connection;

class JdbcTxStatus implements TxStatus {
    static final class RollbackMarker {
        boolean rollbackOnly;
    }

    private final Connection connection;
    private final boolean newTransaction;
    private final boolean readOnly;
    private final JdbcTxStatus suspended;
    private final RollbackMarker rollbackMarker;
    private final int synchronizationBaseline;

    // No requireNonNull on the connection: a SUPPORTS-without-a-transaction or a NOT_SUPPORTED
    // status is deliberately connectionless (see JdbcTxManager#noOp), and rejecting null here
    // turned both of those propagations into an NPE at begin() — the Hibernate manager has
    // always allowed it. resource() reports the real mistake, asking a connectionless status for
    // its connection, where it can name it.
    JdbcTxStatus(
            Connection connection,
            boolean newTransaction,
            boolean readOnly,
            JdbcTxStatus suspended,
            RollbackMarker rollbackMarker,
            int synchronizationBaseline
    ) {
        this.connection = connection;
        this.newTransaction = newTransaction;
        this.readOnly = readOnly;
        this.suspended = suspended;
        this.rollbackMarker = rollbackMarker;
        this.synchronizationBaseline = synchronizationBaseline;
    }

    @Override public boolean isNewTransaction() { return newTransaction; }
    @Override public boolean isReadOnly() { return readOnly; }
    @Override public boolean isRollbackOnly() { return rollbackMarker.rollbackOnly; }
    @Override public void markRollbackOnly() { rollbackMarker.rollbackOnly = true; }

    @Override
    public <R> R resource(Class<R> type) {
        if (connection == null) {
            throw new IllegalStateException("No connection bound to this transaction status");
        }
        return type.cast(connection);
    }

    Connection connection() { return connection; }
    JdbcTxStatus suspended() { return suspended; }
    RollbackMarker rollbackMarker() { return rollbackMarker; }

    /** Index into {@code ResourceRegistry}'s synchronization list where this transaction's own callbacks start. */
    int synchronizationBaseline() { return synchronizationBaseline; }
}
