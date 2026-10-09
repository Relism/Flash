package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.engine.jdbc.connections.spi.ConnectionProvider;
import org.hibernate.engine.spi.SessionFactoryImplementor;

import javax.sql.DataSource;

import java.util.Objects;

public class HibernateTxManager implements TxManager {
    // Per manager, not per class: two databases on one thread never join each other's transaction.
    private final TxResourceKey HIBERNATE_STATUS_KEY = TxResourceKey.of("hibernate.tx.status");
    private final TxResourceKey HIBERNATE_SUSPENDED_KEY = TxResourceKey.of("hibernate.tx.suspended");

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
    public TxStatus begin(TxDefinition definition) {
        return switch (definition.propagation()) {
            case REQUIRED -> ResourceRegistry.isBound(HIBERNATE_STATUS_KEY)
                    ? joinExisting(definition)
                    : beginNew(definition);
            case REQUIRES_NEW -> beginNew(definition);
            // A Session reads inside a transaction: with none to join, SUPPORTS opens one.
            case SUPPORTS -> ResourceRegistry.isBound(HIBERNATE_STATUS_KEY)
                    ? joinExisting(definition)
                    : beginNew(definition);
            case MANDATORY -> {
                if (!ResourceRegistry.isBound(HIBERNATE_STATUS_KEY))
                    throw new IllegalStateException("MANDATORY: no active transaction");
                yield joinExisting(definition);
            }
            case NOT_SUPPORTED -> {
                HibernateTxStatus suspended = suspendIfNeeded();
                yield noOp(definition, suspended);
            }
        };
    }

    private TxStatus beginNew(TxDefinition definition) {
        return beginNew(definition, suspendIfNeeded());
    }

    private TxStatus beginNew(TxDefinition definition, HibernateTxStatus suspended) {
        // Anything already registered belongs to an enclosing transaction this one is nested
        // inside (or suspended over) — see ResourceRegistry#fireSynchronizations.
        int synchronizationBaseline = ResourceRegistry.synchronizationCount();
        Session s = sf.openSession();
        boolean bound = false;
        try {
            s.beginTransaction();
            if (definition.readOnly()) s.setDefaultReadOnly(true);
            if (definition.isolation() != TransactionIsolation.DEFAULT) {
                s.doWork(connection -> connection.setTransactionIsolation(definition.isolation().level()));
            }
            HibernateTxStatus status = new HibernateTxStatus(
                    s,
                    true,
                    definition.readOnly(),
                    suspended,
                    new HibernateTxStatus.RollbackMarker(),
                    synchronizationBaseline
            );
            ResourceRegistry.bind(HIBERNATE_STATUS_KEY, status);
            bound = true;
            return status;
        } catch (RuntimeException e) {
            silentClose(s);
            throw e;
        } catch (Exception e) {
            silentClose(s);
            throw new TxException(e);
        } finally {
            if (!bound && suspended != null) {
                ResourceRegistry.unbind(HIBERNATE_SUSPENDED_KEY);
                ResourceRegistry.bind(HIBERNATE_STATUS_KEY, suspended);
            }
        }
    }

    private TxStatus joinExisting(TxDefinition definition) {
        HibernateTxStatus existing = ResourceRegistry.get(HIBERNATE_STATUS_KEY, HibernateTxStatus.class);
        // A read joins whatever it finds; a write cannot run inside a transaction opened read-only.
        if (!definition.readOnly() && existing.isReadOnly()) {
            throw new TxException("Cannot write inside a read-only transaction");
        }
        // Baseline 0 is never read: a joined status isn't a new transaction, so commit()/rollback()
        // hand it straight back to the transaction it joined without firing anything.
        return new HibernateTxStatus(
                existing.session(),
                false,
                existing.isReadOnly(),
                null,
                existing.rollbackMarker(),
                0
        );
    }

    private TxStatus noOp(TxDefinition definition, HibernateTxStatus suspended) {
        return new HibernateTxStatus(null, false, definition.readOnly(), suspended, new HibernateTxStatus.RollbackMarker(), 0);
    }

    private HibernateTxStatus suspendIfNeeded() {
        HibernateTxStatus suspended = ResourceRegistry.getOrNull(HIBERNATE_STATUS_KEY, HibernateTxStatus.class);
        if (suspended != null) {
            ResourceRegistry.unbind(HIBERNATE_STATUS_KEY);
            ResourceRegistry.bind(HIBERNATE_SUSPENDED_KEY, suspended);
        }
        return suspended;
    }

    @Override
    public void commit(TxStatus status) {
        HibernateTxStatus s = (HibernateTxStatus) status;
        if (!s.isNewTransaction()) {
            resumeIfNeeded(s);
            cleanupIfIdle();
            return;
        }
        TxOutcome outcome = null;
        try {
            if (s.isRollbackOnly() && s.session().getTransaction().isActive()) {
                s.session().getTransaction().rollback();
                outcome = TxOutcome.ROLLED_BACK;
            } else {
                // Still inside the transaction, session still bound: a beforeCommit callback can
                // write through it and land in this same commit. Throwing from there vetoes the
                // commit — see TxSynchronization.
                ResourceRegistry.fireBeforeCommit(s.isReadOnly(), s.synchronizationBaseline());
                s.session().getTransaction().commit();
                outcome = TxOutcome.COMMITTED;
            }
        } catch (RuntimeException e) {
            // A vetoing beforeCommit, or a commit that failed outright: either way nothing was
            // committed, so roll back and let the remaining callbacks hear ROLLED_BACK rather than
            // nothing at all. A rollback failure here is swallowed deliberately — it would mask
            // the exception that actually explains what went wrong, which is the one propagating.
            if (s.session().getTransaction().isActive()) {
                try {
                    s.session().getTransaction().rollback();
                } catch (RuntimeException suppressed) {
                    e.addSuppressed(suppressed);
                }
            }
            outcome = TxOutcome.ROLLED_BACK;
            throw e;
        } finally {
            // Order is load-bearing, and getting it wrong is silent: cleanupIfIdle() calls
            // ResourceRegistry.cleanup(), which removes the very ThreadLocal list of
            // synchronizations still waiting to be fired — firing afterwards saw a freshly
            // initialized empty list and dropped every callback on the floor. cleanupAndResume()
            // still has to come first, so a synchronization that opens its own transaction
            // (Registry#reload() in Pathway does) starts a fresh one instead of joining the
            // session that just committed. rollback() below already had this order right.
            cleanupAndResume(s);
            if (outcome != null) ResourceRegistry.fireSynchronizations(outcome, s.synchronizationBaseline());
            cleanupIfIdle();
        }
    }

    @Override
    public void rollback(TxStatus status) {
        HibernateTxStatus s = (HibernateTxStatus) status;
        if (!s.isNewTransaction()) {
            s.markRollbackOnly();
            resumeIfNeeded(s);
            cleanupIfIdle();
            return;
        }
        try {
            if (s.session().getTransaction().isActive()) {
                s.session().getTransaction().rollback();
            }
        } finally {
            // Same order as commit(): unbind the session first so a callback opening its own
            // transaction gets a fresh one, fire before cleanupIfIdle() can drop the list.
            cleanupAndResume(s);
            ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK, s.synchronizationBaseline());
            cleanupIfIdle();
        }
    }

    private void cleanupAndResume(HibernateTxStatus status) {
        ResourceRegistry.unbind(HIBERNATE_STATUS_KEY);
        silentClose(status.session());
        resumeIfNeeded(status);
    }

    private void cleanupIfIdle() {
        if (!ResourceRegistry.isBound(HIBERNATE_STATUS_KEY) && !ResourceRegistry.isBound(HIBERNATE_SUSPENDED_KEY)) {
            ResourceRegistry.cleanup();
        }
    }

    private void resumeIfNeeded(HibernateTxStatus status) {
        HibernateTxStatus suspended = status.suspended();
        if (suspended == null) {
            suspended = ResourceRegistry.getOrNull(HIBERNATE_SUSPENDED_KEY, HibernateTxStatus.class);
        }
        if (suspended != null) {
            ResourceRegistry.unbind(HIBERNATE_SUSPENDED_KEY);
            ResourceRegistry.bind(HIBERNATE_STATUS_KEY, suspended);
        }
    }

    private void silentClose(Session session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }
}
