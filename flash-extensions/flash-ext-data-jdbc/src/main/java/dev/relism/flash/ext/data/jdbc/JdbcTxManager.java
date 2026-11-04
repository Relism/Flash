package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.ResourceTxManager;
import dev.relism.flash.ext.data.core.TransactionIsolation;
import dev.relism.flash.ext.data.core.TxDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;

/** One connection per transaction, from a {@link DataSource}. */
public class JdbcTxManager extends ResourceTxManager<Connection> {

    private final DataSource ds;

    public JdbcTxManager(DataSource ds) {
        this.ds = Objects.requireNonNull(ds);
    }

    /** Closes the data source when it is closeable, as a pool is; a plain {@code DataSource} holds nothing to release. */
    @Override
    public void close() {
        if (ds instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close the data source", e);
            }
        }
    }

    @Override
    protected Connection acquire() throws SQLException {
        return ds.getConnection();
    }

    @Override
    protected void begin(Connection connection, TxDefinition definition) throws SQLException {
        connection.setAutoCommit(false);
        if (definition.readOnly()) connection.setReadOnly(true);
        if (definition.isolation() != TransactionIsolation.DEFAULT)
            connection.setTransactionIsolation(definition.isolation().level());
    }

    @Override
    protected void commitResource(Connection connection) throws SQLException {
        connection.commit();
    }

    @Override
    protected void rollbackResource(Connection connection) throws SQLException {
        connection.rollback();
    }

    @Override
    protected void release(Connection connection) throws SQLException {
        connection.close();
    }

    /** A connection in auto-commit: the reads of a {@code SUPPORTS} scope cost no BEGIN and no COMMIT. */
    @Override
    protected Connection acquireForReads() throws SQLException {
        Connection connection = ds.getConnection();
        try {
            if (!connection.getAutoCommit()) connection.setAutoCommit(true);
            return connection;
        } catch (SQLException failure) {
            discard(connection);
            throw failure;
        }
    }
}
