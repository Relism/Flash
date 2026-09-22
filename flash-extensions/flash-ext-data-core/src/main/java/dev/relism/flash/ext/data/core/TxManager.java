package dev.relism.flash.ext.data.core;

public interface TxManager extends AutoCloseable {
    TxStatus begin(TxDefinition definition);
    void commit(TxStatus status);
    void rollback(TxStatus status);

    /** Releases what this manager was built on, its connection pool included. {@link dev.relism.flash.ext.data.DataExtension} calls it when the app stops. */
    @Override
    void close();
}
