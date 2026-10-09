package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.*;

class JdbcTxManagerTest {
    private final JdbcTxManager manager = new JdbcTxManager(new TestDataSource());

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

    /**
     * SUPPORTS without an active transaction runs on a connection of its own in autocommit, shared by
     * every SUPPORTS nested in it and closed when the outermost one ends.
     */
    @Test
    void supports_without_active_transaction_reads_on_one_autocommit_connection() throws Exception {
        TxDefinition supports = TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.SUPPORTS);
        TxStatus outer = manager.begin(supports);
        Connection connection = outer.resource(Connection.class);
        assertFalse(outer.isNewTransaction());
        assertTrue(connection.getAutoCommit());

        TxStatus nested = manager.begin(supports.asReadOnly());
        assertSame(connection, nested.resource(Connection.class));
        manager.commit(nested);
        assertFalse(connection.isClosed());

        manager.commit(outer);
        assertTrue(connection.isClosed());
    }

    @Test
    void tx_read_opens_no_transaction_and_joins_the_one_that_is_open() throws Exception {
        Tx tx = new Tx(manager);
        assertTrue(tx.read(() -> tx.resource(Connection.class).getAutoCommit()));
        tx.run(() -> assertSame(tx.resource(Connection.class), tx.read(() -> tx.resource(Connection.class))));
    }

    @Test
    void supports_joins_the_active_transaction() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus read = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.SUPPORTS).asReadOnly());
        assertSame(outer.resource(Connection.class), read.resource(Connection.class));
        manager.commit(read);
        manager.rollback(outer);
    }

    @Test
    void not_supported_suspends_the_active_transaction_and_restores_it_on_commit() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        Connection outerConnection = outer.resource(Connection.class);

        TxStatus suspended = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.NOT_SUPPORTED));
        assertFalse(suspended.isNewTransaction());
        assertThrows(IllegalStateException.class, () -> suspended.resource(Connection.class));
        manager.commit(suspended);

        TxStatus rejoined = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRED));
        assertSame(outerConnection, rejoined.resource(Connection.class), "the suspended transaction must be back");
        manager.rollback(outer);
    }

    @Test
    void mandatory_without_active_transaction_is_rejected() {
        assertThrows(IllegalStateException.class,
                () -> manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.MANDATORY)));
    }
}
