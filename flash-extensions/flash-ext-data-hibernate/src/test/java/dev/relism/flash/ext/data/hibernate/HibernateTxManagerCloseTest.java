package dev.relism.flash.ext.data.hibernate;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.cfg.AvailableSettings;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;

class HibernateTxManagerCloseTest {

    /** Stands in for a pool: closeable, and it records being closed. */
    static final class Pool implements DataSource, AutoCloseable {
        boolean closed;
        @Override public Connection getConnection() throws SQLException { return DriverManager.getConnection("jdbc:h2:mem:tx-close;DB_CLOSE_DELAY=-1"); }
        @Override public Connection getConnection(String user, String password) throws SQLException { return getConnection(); }
        @Override public void close() { closed = true; }
        @Override public <T> T unwrap(Class<T> type) { throw new UnsupportedOperationException(); }
        @Override public boolean isWrapperFor(Class<?> type) { return false; }
        @Override public PrintWriter getLogWriter() { return null; }
        @Override public void setLogWriter(PrintWriter out) {}
        @Override public void setLoginTimeout(int seconds) {}
        @Override public int getLoginTimeout() { return 0; }
        @Override public Logger getParentLogger() { throw new UnsupportedOperationException(); }
    }

    @Test
    void closingTheManagerClosesTheDataSourceHibernateWasGiven() {
        Pool pool = new Pool();
        SessionFactory sf = new MetadataSources(new StandardServiceRegistryBuilder()
                .applySetting(AvailableSettings.JAKARTA_NON_JTA_DATASOURCE, pool)
                .applySetting(AvailableSettings.DIALECT, "org.hibernate.dialect.H2Dialect")
                .build()).buildMetadata().buildSessionFactory();

        new HibernateTxManager(sf).close();

        assertTrue(sf.isClosed());
        assertTrue(pool.closed);
    }
}
