package dev.relism.flash.ext.data.hibernate;

import dev.relism.flash.ext.data.core.*;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Transaction synchronization semantics — the {@code afterCommit}/{@code afterRollback} callbacks
 * {@code Data#afterCommit} exposes, and the contract callers build on: "my callback runs once, on
 * the outermost commit, after the session is gone".
 *
 * <p>None of this was covered before, and the gap was not academic: {@link #afterCommit_fires_on_commit}
 * failed against the original {@code commit()}, which fired synchronizations only after
 * {@code cleanupIfIdle()} had already dropped the ThreadLocal list holding them — so every callback
 * was silently discarded, on every commit, with no error and no log. Downstream that meant an admin
 * write landing in Postgres while the in-memory cache it was supposed to refresh never heard about
 * it until the process restarted.
 */
class HibernateTxManagerSynchronizationTest {

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

    /** A commit() call on a tx already marked rollback-only really rolls back — the callbacks must say so. */
    @Test
    void commit_of_a_rollback_only_tx_fires_the_rollback_callbacks() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        tx.markRollbackOnly();
        ResourceRegistry.addSynchronization(recorder);

        manager.commit(tx);

        assertEquals(List.of("afterRollback", "afterCompletion:ROLLED_BACK"), recorder.calls);
    }

    /**
     * The load-bearing ordering detail: callbacks run <em>after</em> the committed session is
     * unbound, so a callback that opens its own transaction (a cache reload, an outbox drain)
     * gets a fresh one instead of silently joining the transaction that just committed.
     */
    @Test
    void a_synchronization_may_open_its_own_transaction() {
        List<Session> sessionsSeen = new ArrayList<>();
        List<Boolean> wasNewTransaction = new ArrayList<>();

        TxStatus outer = manager.begin(TxDefinition.DEFAULTS);
        Session committedSession = outer.resource(Session.class);
        ResourceRegistry.addSynchronization(new TxSynchronization() {
            @Override
            public void afterCommit() {
                TxStatus own = manager.begin(TxDefinition.DEFAULTS);
                sessionsSeen.add(own.resource(Session.class));
                wasNewTransaction.add(own.isNewTransaction());
                manager.commit(own);
            }
        });

        manager.commit(outer);

        assertEquals(1, sessionsSeen.size(), "the callback must have run");
        assertEquals(List.of(true), wasNewTransaction, "must start its own transaction, not join the committed one");
        assertNotSame(committedSession, sessionsSeen.get(0));
    }

    /** A joined (REQUIRED) inner commit is not a real commit — callbacks wait for the outermost one. */
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

    /** Each callback belongs to one transaction: a second transaction must not re-run the first's. */
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

    /**
     * A REQUIRES_NEW inner transaction suspends the outer one; committing the inner must not drag
     * the still-pending outer transaction's callbacks along with it. They belong to a transaction
     * that has not committed — and may yet roll back, in which case firing {@code afterCommit} for
     * it would be a straight lie.
     */
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

    /** A rolled-back inner REQUIRES_NEW must not fire the outer's callbacks either — same reason, opposite outcome. */
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
