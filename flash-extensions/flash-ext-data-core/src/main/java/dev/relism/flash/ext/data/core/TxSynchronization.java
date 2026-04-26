package dev.relism.flash.ext.data.core;

public interface TxSynchronization {
    default void beforeCommit(boolean readOnly) {}
    default void afterCommit() {}
    default void afterRollback() {}
    default void afterCompletion(TxOutcome outcome) {}
}
