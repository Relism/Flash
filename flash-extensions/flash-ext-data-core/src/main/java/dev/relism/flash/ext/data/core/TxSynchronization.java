package dev.relism.flash.ext.data.core;

/**
 * Lifecycle hooks for one transaction, registered through {@code Data#afterCommit} (or
 * {@link ResourceRegistry#addSynchronization} directly) and fired by the {@link TxManager} that
 * owns the transaction they were registered in.
 *
 * <p>Every callback belongs to exactly one transaction — the innermost one active at registration
 * time — and fires exactly once, when <em>that</em> transaction completes. A joined
 * ({@code REQUIRED}) inner transaction is not a transaction of its own, so callbacks registered
 * inside one wait for the outermost commit; a {@code REQUIRES_NEW} transaction is, so completing
 * it fires only its own callbacks and leaves the suspended outer transaction's alone.
 *
 * <h3>Where each hook sits relative to the commit</h3>
 * <ul>
 *   <li>{@link #beforeCommit(boolean)} — immediately <b>before</b> the real commit, with the
 *       transaction still active and its session/connection still bound. This is the only hook
 *       that can still write through that same resource and have the write land in the same atomic
 *       unit: flush a buffer, stamp an audit row, materialize a derived value. Skipped entirely
 *       when the transaction is already rollback-only, since there is no commit to precede.</li>
 *   <li>{@link #afterCommit()} / {@link #afterRollback()}, then {@link #afterCompletion(TxOutcome)}
 *       — <b>after</b> the transaction has completed and its resource has been unbound. Nothing
 *       done here is part of the transaction: a callback that opens its own transaction gets a
 *       fresh one instead of joining the one that just finished, which is what makes this the
 *       right place to refresh a cache, enqueue a message, or notify anything outside the
 *       database.</li>
 * </ul>
 *
 * <h3>Failure</h3>
 * Throwing from {@link #beforeCommit(boolean)} <b>vetoes the commit</b>: the transaction is rolled
 * back, {@link #afterRollback()}/{@link #afterCompletion(TxOutcome)} fire with
 * {@link TxOutcome#ROLLED_BACK}, and the exception propagates to the caller. That is the point of
 * this hook running before the commit rather than after — it can still refuse.
 *
 * <p>The post-completion hooks have no such power: the transaction is over by the time they run,
 * so an exception from one propagates to the caller but changes nothing already committed, and
 * stops the callbacks queued behind it.
 */
public interface TxSynchronization {

    /**
     * Runs inside the transaction, immediately before it commits — see the interface javadoc.
     * Throwing from here rolls the transaction back instead of committing it.
     *
     * @param readOnly whether the transaction was opened read-only, so a callback that would
     *                 otherwise write can skip work it is not allowed to do
     */
    default void beforeCommit(boolean readOnly) {}

    /** Runs after a successful commit, with the transaction's resource already unbound. */
    default void afterCommit() {}

    /** Runs after a rollback, with the transaction's resource already unbound. */
    default void afterRollback() {}

    /** Runs after {@link #afterCommit()}/{@link #afterRollback()}, whichever applied. */
    default void afterCompletion(TxOutcome outcome) {}
}
