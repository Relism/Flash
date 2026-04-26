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
}
