package dev.relism.flash.ext.data.core;

public record TxDefinition(
        TransactionPropagation propagation,
        TransactionIsolation    isolation,
        boolean                 readOnly
) {
    public static final TxDefinition DEFAULTS = new TxDefinition(
            TransactionPropagation.REQUIRED,
            TransactionIsolation.DEFAULT,
            false
    );

    public TxDefinition withPropagation(TransactionPropagation p) {
        return new TxDefinition(p, isolation, readOnly);
    }

    public TxDefinition withIsolation(TransactionIsolation i) {
        return new TxDefinition(propagation, i, readOnly);
    }


    public TxDefinition asReadOnly() {
        return new TxDefinition(propagation, isolation, true);
    }
}
