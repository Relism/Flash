package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.ResourceTxManager;
import dev.relism.flash.ext.data.core.TransactionIsolation;
import dev.relism.flash.ext.data.core.TxDefinition;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import javax.sql.DataSource;

import java.util.Objects;

/** One {@link Session} per transaction. A session reads inside a transaction, so {@code SUPPORTS} with none to join opens one. */
public class HibernateTxManager extends ResourceTxManager<Session> {

    private final SessionFactory sf;

    public HibernateTxManager(SessionFactory sessionFactory) {
        this.sf = Objects.requireNonNull(sessionFactory);
    }

    /**
     * Closes the session factory, then the data source it was given ({@code jakarta.persistence.nonJtaDataSource}
     * or {@code hibernate.connection.datasource}): Hibernate stops a pool it built itself, never one handed to it.
     */
    @Override
    public void close() {
        ConnectionProvider connections = sf.unwrap(SessionFactoryImplementor.class).getServiceRegistry().getService(ConnectionProvider.class);
        DataSource ds = connections != null && connections.isUnwrappableAs(DataSource.class) ? connections.unwrap(DataSource.class) : null;
        sf.close();
        if (ds instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Failed to close the data source", e);
            }
        }
    }

    @Override
    protected Session acquire() {
        return sf.openSession();
    }

    @Override
    protected void begin(Session session, TxDefinition definition) {
        session.beginTransaction();
        if (definition.readOnly()) session.setDefaultReadOnly(true);
        if (definition.isolation() != TransactionIsolation.DEFAULT)
            session.doWork(connection -> connection.setTransactionIsolation(definition.isolation().level()));
    }

    @Override
    protected void commitResource(Session session) {
        session.getTransaction().commit();
    }

    @Override
    protected void rollbackResource(Session session) {
        if (session.getTransaction().isActive()) session.getTransaction().rollback();
    }

    @Override
    protected void release(Session session) {
        session.close();
    }
}
