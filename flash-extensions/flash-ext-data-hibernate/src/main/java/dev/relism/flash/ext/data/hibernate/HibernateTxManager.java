package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.util.Objects;

public class HibernateTxManager implements TxManager {
    private static final TxResourceKey HIBERNATE_STATUS_KEY = TxResourceKey.of("hibernate.tx.status");
    private static final TxResourceKey HIBERNATE_SUSPENDED_KEY = TxResourceKey.of("hibernate.tx.suspended");

    private final SessionFactory sf;

    public HibernateTxManager(SessionFactory sessionFactory) {
        this.sf = Objects.requireNonNull(sessionFactory);
    }

    @Override
    public TxStatus begin(TxDefinition definition) {
        return switch (definition.propagation()) {
            case REQUIRED -> ResourceRegistry.isBound(HIBERNATE_STATUS_KEY)
                    ? joinExisting(definition)
                    : beginNew(definition);
            case REQUIRES_NEW -> beginNew(definition);
            case SUPPORTS -> ResourceRegistry.isBound(HIBERNATE_STATUS_KEY)
                    ? joinExisting(definition)
                    : noOp(definition);
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
                    new HibernateTxStatus.RollbackMarker()
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
        if (definition.readOnly() && !existing.isReadOnly()) {
            throw new TxException("Cannot join read-write tx as read-only");
        }
        return new HibernateTxStatus(
                existing.session(),
                false,
                definition.readOnly(),
                null,
                existing.rollbackMarker()
        );
    }

    private TxStatus noOp(TxDefinition definition) {
        return noOp(definition, null);
    }

    private TxStatus noOp(TxDefinition definition, HibernateTxStatus suspended) {
        return new HibernateTxStatus(null, false, definition.readOnly(), suspended, new HibernateTxStatus.RollbackMarker());
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
        try {
            if (s.isRollbackOnly() && s.session().getTransaction().isActive()) {
                s.session().getTransaction().rollback();
                ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK);
            } else {
                s.session().getTransaction().commit();
                ResourceRegistry.fireSynchronizations(TxOutcome.COMMITTED);
            }
        } finally {
            cleanupAndResume(s);
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
            ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK);
        } finally {
            cleanupAndResume(s);
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
