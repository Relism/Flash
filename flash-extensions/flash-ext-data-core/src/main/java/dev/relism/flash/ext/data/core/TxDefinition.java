package dev.relism.flash.ext.data.core;

public record TxDefinition(
        TransactionPropagation propagation,
        TransactionIsolation    isolation,
        boolean                 readOnly,
        String                  label
) {
    public static final TxDefinition DEFAULTS = new TxDefinition(
            TransactionPropagation.REQUIRED,
            TransactionIsolation.DEFAULT,
            false,
            null
    );

    public TxDefinition withPropagation(TransactionPropagation p) {
        return new TxDefinition(p, isolation, readOnly, label);
    }

    public TxDefinition withIsolation(TransactionIsolation i) {
        return new TxDefinition(propagation, i, readOnly, label);
    }

    public TxDefinition withReadOnly(boolean ro) {
        return new TxDefinition(propagation, isolation, ro, label);
    }

    public TxDefinition asReadOnly() {
        return new TxDefinition(propagation, isolation, true, label);
    }
}
