package dev.relism.flash.ext.data.jdbc;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

/**
 * A bare in-memory H2 {@link DataSource} — one fresh connection per {@code getConnection()}, which
 * is all {@code JdbcTxManager} needs to exercise real commit/rollback and suspension. Every method
 * outside the two {@code getConnection} overloads throws: nothing under test calls them, and a
 * loud failure beats a silent stub if that ever changes.
 */
final class TestDataSource implements DataSource {

    static final String URL = "jdbc:h2:mem:tx-jdbc;DB_CLOSE_DELAY=-1";

    @Override
    public Connection getConnection() throws SQLException {
        return DriverManager.getConnection(URL);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return DriverManager.getConnection(URL, username, password);
    }

    @Override public <T> T unwrap(Class<T> iface) { throw new UnsupportedOperationException(); }
    @Override public boolean isWrapperFor(Class<?> iface) { return false; }
    @Override public PrintWriter getLogWriter() { throw new UnsupportedOperationException(); }
    @Override public void setLogWriter(PrintWriter out) { throw new UnsupportedOperationException(); }
    @Override public void setLoginTimeout(int seconds) { throw new UnsupportedOperationException(); }
    @Override public int getLoginTimeout() { return 0; }
    @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
}
