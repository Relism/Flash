package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTxManagerTest {
    private final JdbcTxManager manager = new JdbcTxManager(dataSource());

    @AfterEach
    void cleanup() {
        ResourceRegistry.clear();
    }

    @Test
    void required_starts_new_when_absent() {
        TxStatus s = manager.begin(TxDefinition.DEFAULTS);
        assertTrue(s.isNewTransaction());
        assertDoesNotThrow(() -> manager.commit(s));
    }

    @Test
    void required_joins_existing_when_present() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRED));
        assertSame(outer.resource(Connection.class), inner.resource(Connection.class));
        assertFalse(inner.isNewTransaction());
        manager.rollback(outer);
    }

    @Test
    void requires_new_creates_distinct_connection() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW));
        assertNotSame(outer.resource(Connection.class), inner.resource(Connection.class));
        manager.commit(inner);
        manager.rollback(outer);
    }

    @Test
    void rollback_on_joined_marks_outer_rollback_only() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRED));
        manager.rollback(inner);
        assertTrue(outer.isRollbackOnly());
        manager.rollback(outer);
    }

    private static DataSource dataSource() {
        return new DataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                return DriverManager.getConnection("jdbc:h2:mem:tx-jdbc;DB_CLOSE_DELAY=-1");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                return DriverManager.getConnection("jdbc:h2:mem:tx-jdbc;DB_CLOSE_DELAY=-1", username, password);
            }

            @Override
            public <T> T unwrap(Class<T> iface) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean isWrapperFor(Class<?> iface) {
                return false;
            }

            @Override
            public java.io.PrintWriter getLogWriter() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLogWriter(java.io.PrintWriter out) {
                throw new UnsupportedOperationException();
            }

            @Override
            public void setLoginTimeout(int seconds) {
                throw new UnsupportedOperationException();
            }

            @Override
            public int getLoginTimeout() {
                return 0;
            }

            @Override
            public java.util.logging.Logger getParentLogger() {
                throw new UnsupportedOperationException();
            }
        };
    }
}
