package dev.relism.flash.http2.frame;

import java.util.concurrent.atomic.AtomicReference;

/**
 * A Vyukov-style intrusive multi-producer, single-consumer queue of {@link WriteIntent}s.
 * "Intrusive" means the queued object <em>is</em> the node — {@link WriteIntent#mpscNext()} /
 * {@link WriteIntent#setMpscNext} supply the linkage — so {@link #offer} allocates nothing: one
 * {@link AtomicReference#getAndSet} CAS and that is the entire cost.
 *
 * <h3>Only {@link Http2FrameWriter} calls {@link #poll()}</h3>
 * This queue is safe for any number of concurrent {@link #offer} callers, but {@link #poll()}
 * must only ever be called by the single thread currently holding the writer's lock — exactly
 * the invariant {@code Http2FrameWriter} maintains (it never calls {@code poll()} without
 * holding the lock). Calling {@code poll()} from two threads concurrently is undefined.
 *
 * <h3>The stub node and the "inconsistent" result</h3>
 * The queue always contains at least one node — a private, singleton {@code stub} — which lets
 * {@link #offer} and {@link #poll} both proceed without ever observing a literal {@code null}
 * head. A subtlety of this algorithm (documented here because it surprises readers unfamiliar
 * with it, and it is the reason {@code Http2FrameWriter}'s drain loop is itself a loop, not a
 * single pass): {@link #poll()} can return {@code null} even when {@link #offer} has completed
 * and is "logically" enqueued, if that producer's {@code getAndSet} (which publishes the new
 * tail pointer) has completed but its following {@code setMpscNext} (which links the *previous*
 * tail to it) has not yet landed. This is a momentary, self-correcting race — the next
 * {@code poll()} call (even from the same thread, immediately after) will see it — never a
 * permanent loss. {@code Http2FrameWriter}'s lost-wakeup-avoidance protocol (see its Javadoc)
 * already retries in exactly the way this requires.
 */
final class IntrusiveMpscQueue {

    /**
     * Sentinel node that is never returned by {@link #poll()} and never appears anywhere except
     * internally. Its own {@code mpscNext} field is the only piece of mutable state on it.
     */
    private static final class Stub implements WriteIntent {
        private volatile WriteIntent next;

        @Override public byte[] buffer() { throw new UnsupportedOperationException("stub node"); }
        @Override public int offset() { throw new UnsupportedOperationException("stub node"); }
        @Override public int length() { throw new UnsupportedOperationException("stub node"); }
        @Override public WriteIntent mpscNext() { return next; }
        @Override public void setMpscNext(WriteIntent next) { this.next = next; }
    }

    private final Stub stub = new Stub();
    private final AtomicReference<WriteIntent> head = new AtomicReference<>(stub);
    private WriteIntent tail = stub; // consumer-only; never touched by offer()

    /** Enqueues {@code node}. Safe from any number of concurrent threads. Zero allocation. */
    void offer(WriteIntent node) {
        node.setMpscNext(null);
        WriteIntent prev = head.getAndSet(node);
        prev.setMpscNext(node);
    }

    /**
     * Dequeues the next intent, or {@code null} if the queue is empty <em>or</em> a producer is
     * momentarily mid-{@link #offer} — see the class Javadoc. Single-consumer only.
     */
    WriteIntent poll() {
        WriteIntent t = tail;
        WriteIntent next = t.mpscNext();

        if (t == stub) {
            if (next == null) {
                return null; // genuinely empty
            }
            tail = next;
            t = next;
            next = t.mpscNext();
        }

        if (next != null) {
            tail = next;
            return t;
        }

        WriteIntent h = head.get();
        if (t != h) {
            return null; // producer mid-offer; momentary, retry later
        }

        // t is the last real node and head hasn't moved past it: park the stub here so the
        // next poll() (once a future offer() lands) has somewhere to advance from, then check
        // whether t already gained a follower while we were doing this.
        offer(stub);
        next = t.mpscNext();
        if (next != null) {
            tail = next;
            return t;
        }
        return null;
    }

    /** Cheap, conservative "might there be work" check — never a false negative, may be a false
     *  positive (harmless: the caller just attempts a {@code tryLock()} that finds nothing). */
    boolean hasWork() {
        return head.get() != tail;
    }
}
