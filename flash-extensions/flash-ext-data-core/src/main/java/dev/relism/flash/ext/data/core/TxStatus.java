package dev.relism.flash.ext.data.core;

public interface TxStatus {
    boolean isNewTransaction();
    boolean isReadOnly();
    boolean isRollbackOnly();
    void markRollbackOnly();
    <R> R resource(Class<R> type);
}
