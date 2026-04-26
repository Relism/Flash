package dev.relism.flash.ext.data.core;

public interface TxManager {
    TxStatus begin(TxDefinition definition);
    void commit(TxStatus status);
    void rollback(TxStatus status);
}
