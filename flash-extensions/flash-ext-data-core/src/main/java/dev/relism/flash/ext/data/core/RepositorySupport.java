package dev.relism.flash.ext.data.core;

public abstract class RepositorySupport<T, ID> {
    private final Tx tx;
    private final TxDefinition rw = TxDefinition.DEFAULTS
            .withPropagation(TransactionPropagation.REQUIRED);
    private final TxDefinition ro = rw.asReadOnly();

    protected RepositorySupport(Tx tx) {
        this.tx = tx;
    }

    protected final Tx tx() {
        return tx;
    }

    protected final <R> R roQuery(Tx.TxCallable<R> work) {
        return tx.call(ro, work);
    }

    protected final <R> R rwQuery(Tx.TxCallable<R> work) {
        return tx.call(rw, work);
    }
}
