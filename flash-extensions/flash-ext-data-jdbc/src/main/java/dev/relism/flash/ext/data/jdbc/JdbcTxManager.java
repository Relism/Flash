package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.ResourceTxManager;
import dev.relism.flash.ext.data.core.TransactionIsolation;
import dev.relism.flash.ext.data.core.TxDefinition;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** One connection per transaction, from a {@link DataSource}. */
public class JdbcTxManager extends ResourceTxManager<Connection> {

    private final DataSource ds;
    /** What a transaction changed on a connection, put back before it returns to the data source. */
    private final Map<Connection, Runnable> restore = new ConcurrentHashMap<>();

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
        boolean readOnly = definition.readOnly() && !connection.isReadOnly();
        // Asked only when a level is: some drivers answer getTransactionIsolation with a round trip.
        int level = definition.isolation().level();
        int was = definition.isolation() == TransactionIsolation.DEFAULT ? level : connection.getTransactionIsolation();
        // Registered first: a setting that fails after another was applied still leaves the way back.
        if (readOnly || was != level) restore.put(connection, () -> {
            try {
                if (readOnly) connection.setReadOnly(false);
                if (was != level) connection.setTransactionIsolation(was);
            } catch (SQLException ignored) {   // the connection is being given up
            }
        });
        if (readOnly) connection.setReadOnly(true);
        if (was != level) connection.setTransactionIsolation(level);
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
        Runnable back = restore.remove(connection);
        if (back != null) back.run();
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
