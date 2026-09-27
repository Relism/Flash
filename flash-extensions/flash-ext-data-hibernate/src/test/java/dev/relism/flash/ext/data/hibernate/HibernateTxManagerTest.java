package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HibernateTxManagerTest {
    static SessionFactory sf;
    static HibernateTxManager manager;

    @BeforeAll
    static void setup() {
        sf = TestHelper.buildSessionFactory();
        manager = new HibernateTxManager(sf);
    }

    @AfterAll
    static void teardown() {
        if (sf != null) {
            sf.close();
        }
    }

    @AfterEach
    void cleanup() {
        ResourceRegistry.clear();
    }

    @Test
    void required_starts_new_when_absent() {
        TxStatus s = manager.begin(TxDefinition.DEFAULTS);
        assertNotNull(s.resource(Session.class));
        assertTrue(s.isNewTransaction());
        assertDoesNotThrow(() -> manager.commit(s));
    }

    @Test
    void required_joins_existing_when_present() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRED));
        assertSame(outer.resource(Session.class), inner.resource(Session.class));
        manager.rollback(outer);
    }

    @Test
    void requires_new_uses_separate_session() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW));
        assertNotSame(outer.resource(Session.class), inner.resource(Session.class));
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

    /** SUPPORTS without an active transaction yields a sessionless status: not a transaction, no session to hand out. */
    @Test
    void supports_without_active_transaction_is_a_sessionless_no_op() {
        TxStatus s = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.SUPPORTS));

        assertFalse(s.isNewTransaction());
        assertThrows(IllegalStateException.class, () -> s.resource(Session.class));
        assertDoesNotThrow(() -> manager.commit(s));
    }

    @Test
    void not_supported_suspends_the_active_transaction_and_restores_it_on_commit() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        Session outerSession = outer.resource(Session.class);

        TxStatus suspended = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.NOT_SUPPORTED));
        assertFalse(suspended.isNewTransaction());
        assertThrows(IllegalStateException.class, () -> suspended.resource(Session.class));
        manager.commit(suspended);

        TxStatus rejoined = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRED));
        assertSame(outerSession, rejoined.resource(Session.class), "the suspended transaction must be back");
        manager.rollback(outer);
    }

    @Test
    void mandatory_without_active_transaction_is_rejected() {
        assertThrows(IllegalStateException.class,
                () -> manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.MANDATORY)));
    }

    /** A read inside a write joins it, and stays in its session; a write inside a read-only transaction is refused. */
    @Test
    void a_read_joins_a_write_and_a_write_cannot_join_a_read() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus read = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRED).asReadOnly());
        assertFalse(read.isNewTransaction());
        manager.commit(read);
        manager.rollback(outer);

        TxStatus readOnly = manager.begin(TxDefinition.DEFAULTS.asReadOnly());
        assertThrows(TxException.class, () -> manager.begin(TxDefinition.DEFAULTS));
        manager.rollback(readOnly);
    }
}
