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
 * Transaction synchronization semantics — the {@code beforeCommit}/{@code afterCommit}/
 * {@code afterRollback} callbacks {@code Data#afterCommit} exposes, and the contract callers build
 * on: "my callback runs once, for my transaction, on the right side of the commit".
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

        @Override public void beforeCommit(boolean readOnly) { calls.add("beforeCommit:" + readOnly); }
        @Override public void afterCommit() { calls.add("afterCommit"); }
        @Override public void afterRollback() { calls.add("afterRollback"); }
        @Override public void afterCompletion(TxOutcome outcome) { calls.add("afterCompletion:" + outcome); }
    }

    private static final List<String> COMMITTED = List.of("beforeCommit:false", "afterCommit", "afterCompletion:COMMITTED");
    private static final List<String> ROLLED_BACK = List.of("afterRollback", "afterCompletion:ROLLED_BACK");

    @Test
    void afterCommit_fires_on_commit() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(recorder);

        manager.commit(tx);

        assertEquals(COMMITTED, recorder.calls);
    }

    @Test
    void afterRollback_fires_on_rollback() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(recorder);

        manager.rollback(tx);

        assertEquals(ROLLED_BACK, recorder.calls);
    }

    /** A commit() call on a tx already marked rollback-only really rolls back — and has no commit to precede. */
    @Test
    void commit_of_a_rollback_only_tx_rolls_back_without_running_beforeCommit() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        tx.markRollbackOnly();
        ResourceRegistry.addSynchronization(recorder);

        manager.commit(tx);

        assertEquals(ROLLED_BACK, recorder.calls);
    }

    /** beforeCommit runs inside the transaction: session still bound, transaction still active. */
    @Test
    void beforeCommit_runs_while_the_transaction_is_still_active() {
        List<Boolean> stillActive = new ArrayList<>();
        List<Session> sessionSeen = new ArrayList<>();

        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        Session session = tx.resource(Session.class);
        ResourceRegistry.addSynchronization(new TxSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                stillActive.add(session.getTransaction().isActive());
                // MANDATORY only succeeds while a transaction is bound — proof this runs inside it.
                TxStatus joined = manager.begin(TxDefinition.DEFAULTS.withPropagation(TransactionPropagation.MANDATORY));
                sessionSeen.add(joined.resource(Session.class));
                manager.commit(joined);
            }
        });

        manager.commit(tx);

        assertEquals(List.of(true), stillActive, "the transaction must not have committed yet");
        assertSame(session, sessionSeen.get(0), "the same session must still be bound, so writes land in this commit");
    }

    @Test
    void beforeCommit_is_told_whether_the_transaction_is_read_only() {
        Recorder readWrite = new Recorder();
        TxStatus rw = manager.begin(TxDefinition.DEFAULTS);
        ResourceRegistry.addSynchronization(readWrite);
        manager.commit(rw);

        Recorder readOnly = new Recorder();
        TxStatus ro = manager.begin(TxDefinition.DEFAULTS.asReadOnly());
        ResourceRegistry.addSynchronization(readOnly);
        manager.commit(ro);

        assertEquals("beforeCommit:false", readWrite.calls.get(0));
        assertEquals("beforeCommit:true", readOnly.calls.get(0));
    }

    /** Throwing from beforeCommit is a veto: no commit, the rollback callbacks run, the exception propagates. */
    @Test
    void a_throwing_beforeCommit_vetoes_the_commit() {
        Recorder recorder = new Recorder();
        TxStatus tx = manager.begin(TxDefinition.DEFAULTS);
        Session session = tx.resource(Session.class);
        ResourceRegistry.addSynchronization(new TxSynchronization() {
            @Override public void beforeCommit(boolean readOnly) { throw new IllegalStateException("veto"); }
        });
        ResourceRegistry.addSynchronization(recorder);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> manager.commit(tx));

        assertEquals("veto", thrown.getMessage());
        assertFalse(session.getTransaction().isActive(), "the vetoed transaction must be rolled back, not left open");
        assertEquals(ROLLED_BACK, recorder.calls, "the surviving callbacks must hear ROLLED_BACK, not silence");
    }

    /**
     * The load-bearing ordering detail: post-completion callbacks run <em>after</em> the committed
     * session is unbound, so a callback that opens its own transaction (a cache reload, an outbox
     * drain) gets a fresh one instead of silently joining the transaction that just committed.
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
        assertEquals(COMMITTED, recorder.calls);
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

        assertEquals(COMMITTED, innerSync.calls);
        assertEquals(List.of(), outerSync.calls, "the outer transaction has not committed yet");

        manager.commit(outer);
        assertEquals(COMMITTED, outerSync.calls);
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
        assertEquals(COMMITTED, outerSync.calls);
    }
}
