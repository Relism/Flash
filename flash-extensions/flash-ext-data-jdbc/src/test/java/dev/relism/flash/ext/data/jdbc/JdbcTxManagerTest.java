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

    /** Ending a transaction joined inside a REQUIRES_NEW leaves the new one current, not the one it suspended. */
    @Test
    void a_join_inside_requires_new_keeps_the_new_transaction_current() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW));
        manager.commit(manager.begin(TxDefinition.DEFAULTS));
        TxStatus next = manager.begin(TxDefinition.DEFAULTS);
        assertSame(inner.resource(Connection.class), next.resource(Connection.class));
        manager.commit(next);
        manager.commit(inner);
        assertSame(outer.resource(Connection.class), manager.begin(TxDefinition.DEFAULTS).resource(Connection.class));
        manager.rollback(outer);
    }

    @Test
    void a_rolled_back_join_inside_requires_new_keeps_the_new_transaction_current() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW));
        manager.rollback(manager.begin(TxDefinition.DEFAULTS));
        TxStatus next = manager.begin(TxDefinition.DEFAULTS);
        assertSame(inner.resource(Connection.class), next.resource(Connection.class));
        manager.rollback(next);
        manager.rollback(inner);
        manager.rollback(outer);
    }

    /** A callback of a finished REQUIRES_NEW runs before the outer transaction is resumed: what it opens is its own. */
    @Test
    void a_callback_opens_a_transaction_of_its_own_not_the_suspended_one() {
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW));
        boolean[] own = new boolean[1];
        ResourceRegistry.addSynchronization(new TxSynchronization() {
            @Override public void afterCommit() {
                TxStatus opened = manager.begin(TxDefinition.DEFAULTS);
                own[0] = opened.isNewTransaction() && opened.resource(Connection.class) != outer.resource(Connection.class);
                manager.commit(opened);
            }
        });
        manager.commit(inner);
        assertTrue(own[0]);
        assertSame(outer.resource(Connection.class), manager.begin(TxDefinition.DEFAULTS).resource(Connection.class));
        manager.rollback(outer);
    }

    /** One manager finishing never clears another's transaction on the same thread. */
    @Test
    void a_second_manager_leaves_the_first_one_s_transaction_bound() {
        JdbcTxManager other = new JdbcTxManager(new TestDataSource());
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus foreign = other.begin(TxDefinition.DEFAULTS);
        assertTrue(foreign.isNewTransaction(), "a manager does not join another's transaction");
        assertNotSame(outer.resource(Connection.class), foreign.resource(Connection.class));
        other.commit(foreign);
        TxStatus joined = manager.begin(TxDefinition.DEFAULTS);
        assertFalse(joined.isNewTransaction());
        assertSame(outer.resource(Connection.class), joined.resource(Connection.class));
        manager.rollback(outer);
    }

    /** An Error from beforeCommit vetoes the commit like an exception: rolled back, and the callbacks hear it. */
    @Test
    void an_error_from_before_commit_rolls_back() {
        TxStatus status = manager.begin(TxDefinition.DEFAULTS);
        TxOutcome[] heard = new TxOutcome[1];
        ResourceRegistry.addSynchronization(new TxSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw new AssertionError("veto"); }
            @Override public void afterCompletion(TxOutcome outcome) { heard[0] = outcome; }
        });
        assertThrows(AssertionError.class, () -> manager.commit(status));
        assertEquals(TxOutcome.ROLLED_BACK, heard[0]);
    }

    /** A connection comes back as it was handed out, whatever the transaction set on it. */
    @Test
    void a_connection_returns_with_its_read_only_and_isolation_restored() throws Exception {
        Connection real = new TestDataSource().getConnection();
        Connection kept = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) ->
                        method.getName().equals("close") ? null : method.invoke(real, args));
        int isolation = real.getTransactionIsolation();
        JdbcTxManager pooled = new JdbcTxManager(new TestDataSource() {
            @Override public Connection getConnection() { return kept; }
        });
        pooled.commit(pooled.begin(TxDefinition.DEFAULTS.withIsolation(TransactionIsolation.SERIALIZABLE).asReadOnly()));
        assertFalse(real.isReadOnly());
        assertEquals(isolation, real.getTransactionIsolation());
        real.close();
    }

    /** A setting that fails after another was applied still puts the first one back. */
    @Test
    void a_connection_returns_restored_when_a_setting_fails_half_way() throws Exception {
        Connection real = new TestDataSource().getConnection();
        Connection refusing = (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "close" -> null;
                    case "setTransactionIsolation" -> throw new java.sql.SQLException("level not supported");
                    default -> method.invoke(real, args);
                });
        JdbcTxManager pooled = new JdbcTxManager(new TestDataSource() {
            @Override public Connection getConnection() { return refusing; }
        });
        assertThrows(RuntimeException.class, () ->
                pooled.begin(TxDefinition.DEFAULTS.withIsolation(TransactionIsolation.SERIALIZABLE).asReadOnly()));
        assertFalse(real.isReadOnly());
        real.close();
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
