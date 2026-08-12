package dev.relism.flash.ext.data.jdbc;

import dev.relism.flash.ext.data.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transaction synchronization semantics for the JDBC manager — the same contract {@code
 * HibernateTxManagerSynchronizationTest} pins down for the Hibernate one, kept deliberately
 * parallel: the two managers are interchangeable behind {@code TxManager}, so a callback must not
 * observe a different lifecycle depending on which one is installed.
 */
class JdbcTxManagerSynchronizationTest {

    private final JdbcTxManager manager = new JdbcTxManager(new TestDataSource());

    @AfterEach
    void cleanup() {
        ResourceRegistry.clear();
    }

    /** Records which callbacks ran, in order — order is part of the contract, not just the fact they ran. */
    private static final class Recorder implements TxSynchronization {
        final List<String> calls = new ArrayList<>();

        @Override public void afterCommit() { calls.add("afterCommit"); }
        @Override public void afterRollback() { calls.add("afterRollback"); }
        @Override public void afterCompletion(TxOutcome outcome) { calls.add("afterCompletion:" + outcome); }
    }

    @Test
    void afterCommit_fires_on_commit() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(recorder);

        manager.commit(tx);

        assertEquals(List.of("afterCommit", "afterCompletion:COMMITTED"), recorder.calls);
    }

    @Test
    void afterRollback_fires_on_rollback() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(recorder);

        manager.rollback(tx);

        assertEquals(List.of("afterRollback", "afterCompletion:ROLLED_BACK"), recorder.calls);
    }

    @Test
    void commit_of_a_rollback_only_tx_fires_the_rollback_callbacks() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        tx.markRollbackOnly();
        ResourceRegistry.addSynchronization(recorder);

        manager.commit(tx);

        assertEquals(List.of("afterRollback", "afterCompletion:ROLLED_BACK"), recorder.calls);
    }

    /** Callbacks run after the committed connection is unbound, so opening a transaction gets a fresh one. */
    @Test
    void a_synchronization_may_open_its_own_transaction() {
        List<Connection> connectionsSeen = new ArrayList<>();
        List<Boolean> wasNewTransaction = new ArrayList<>();

        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        Connection committedConnection = outer.resource(Connection.class);
        ResourceRegistry.addSynchronization(new TxSynchronization() {
            @Override
            public void afterCommit() {
                TxStatus own = manager.begin(TxDefinition.DEFAULTS);
                connectionsSeen.add(own.resource(Connection.class));
                wasNewTransaction.add(own.isNewTransaction());
                manager.commit(own);
            }
        });

        manager.commit(outer);

        assertEquals(1, connectionsSeen.size(), "the callback must have run");
        assertEquals(List.of(true), wasNewTransaction, "must start its own transaction, not join the committed one");
        assertNotSame(committedConnection, connectionsSeen.get(0));
    }

    @Test
    void a_joined_commit_defers_synchronizations_to_the_outermost_commit() {
        Recorder recorder = new Recorder();
        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRED));
        ResourceRegistry.addSynchronization(recorder);

        manager.commit(inner);
        assertEquals(List.of(), recorder.calls, "the joined commit did not commit anything yet");

        manager.commit(outer);
        assertEquals(List.of("afterCommit", "afterCompletion:COMMITTED"), recorder.calls);
    }

    @Test
    void synchronizations_do_not_leak_into_the_next_transaction() {
        Recorder recorder = new Recorder();
        TxStatus first = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(recorder);
        manager.commit(first);
        recorder.calls.clear();

        TxStatus second = manager.begin(TxDefinition.DEFAULTS);
        manager.commit(second);

        assertEquals(List.of(), recorder.calls);
    }

    @Test
    void a_requires_new_commit_leaves_the_suspended_transactions_synchronizations_alone() {
        Recorder outerSync = new Recorder();
        Recorder innerSync = new Recorder();

        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(outerSync);

        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW));
        ResourceRegistry.addSynchronization(innerSync);
        manager.commit(inner);

        assertEquals(List.of("afterCommit", "afterCompletion:COMMITTED"), innerSync.calls);
        assertEquals(List.of(), outerSync.calls, "the outer transaction has not committed yet");

        manager.commit(outer);
        assertEquals(List.of("afterCommit", "afterCompletion:COMMITTED"), outerSync.calls);
    }

    @Test
    void a_requires_new_rollback_leaves_the_suspended_transactions_synchronizations_alone() {
        Recorder outerSync = new Recorder();

        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(outerSync);

        TxStatus inner = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.REQUIRES_NEW));
        manager.rollback(inner);

        assertEquals(List.of(), outerSync.calls, "the outer transaction is still open");

        manager.commit(outer);
        assertEquals(List.of("afterCommit", "afterCompletion:COMMITTED"), outerSync.calls);
    }
}
