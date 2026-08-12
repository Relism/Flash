package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.TxStatus;
import org.hibernate.Session;

class HibernateTxStatus implements TxStatus {
    static final class RollbackMarker {
        boolean rollbackOnly;
    }

    private final Session session;
    private final boolean newTransaction;
    private final boolean readOnly;
    private final HibernateTxStatus suspended;
    private final RollbackMarker rollbackMarker;
    private final int synchronizationBaseline;

    HibernateTxStatus(
            Session session,
            boolean newTransaction,
            boolean readOnly,
            HibernateTxStatus suspended,
            RollbackMarker rollbackMarker,
            int synchronizationBaseline
    ) {
        this.session = session;
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
        if (session == null) {
            throw new IllegalStateException("No session bound to this transaction status");
        }
        return type.cast(session);
    }

    Session session() { return session; }
    HibernateTxStatus suspended() { return suspended; }
    RollbackMarker rollbackMarker() { return rollbackMarker; }

    /** Index into {@code ResourceRegistry}'s synchronization list where this transaction's own callbacks start. */
    int synchronizationBaseline() { return synchronizationBaseline; }
}
