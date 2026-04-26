package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.util.Objects;

public class HibernateTxManager implements TxManager {
    private static final TxResourceKey HIBERNATE_STATUS_KEY = TxResourceKey.of("hibernate.tx.status");

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
            case MANDATORY -> {
                if (!ResourceRegistry.isBound(HIBERNATE_STATUS_KEY))
                    throw new IllegalStateException("MANDATORY: no active transaction");
                yield joinExisting(definition);
            }
            case NOT_SUPPORTED -> throw new UnsupportedOperationException("NOT_SUPPORTED is not implemented");
        };
    }

    private TxStatus beginNew(TxDefinition definition) {
        HibernateTxStatus suspended = ResourceRegistry.getOrNull(HIBERNATE_STATUS_KEY, HibernateTxStatus.class);
        if (suspended != null) {
            ResourceRegistry.unbind(HIBERNATE_STATUS_KEY);
        }
        Session s = sf.openSession();
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
        return status;
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

    @Override
    public void commit(TxStatus status) {
        HibernateTxStatus s = (HibernateTxStatus) status;
        if (!s.isNewTransaction()) {
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
        }
    }

    @Override
    public void rollback(TxStatus status) {
        HibernateTxStatus s = (HibernateTxStatus) status;
        if (!s.isNewTransaction()) {
            s.markRollbackOnly();
            return;
        }
        try {
            if (s.session().getTransaction().isActive()) {
                s.session().getTransaction().rollback();
            }
            ResourceRegistry.fireSynchronizations(TxOutcome.ROLLED_BACK);
        } finally {
            cleanupAndResume(s);
        }
    }

    private void cleanupAndResume(HibernateTxStatus status) {
        ResourceRegistry.unbind(HIBERNATE_STATUS_KEY);
        status.session().close();
        HibernateTxStatus suspended = status.suspended();
        if (suspended != null) {
            ResourceRegistry.bind(HIBERNATE_STATUS_KEY, suspended);
        }
    }
}
