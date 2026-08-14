package dev.relism.flash.transport;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A bounded cache of {@link ConnectionScratch} instances, reused across connections instead of
 * being allocated and garbage-collected per connection.
 *
 * <p>This is a <em>cache</em>, not a leak-free arena: a burst of 100 000 concurrent connections
 * still allocates 100 000 {@link ConnectionScratch} instances (one per connection, since each
 * connection needs its own for as long as it is open), but only {@link #bound} of them survive
 * being released back to the pool afterward — the rest are simply dropped for the garbage
 * collector, exactly as they would have been without this class. What the pool buys is avoiding
 * repeated allocation for the common case of many short-lived or sequential connections sharing
 * a bounded set of scratch objects.
 *
 * <h3>Thread-safety</h3>
 * {@link #acquire()} and {@link #release} are safe to call concurrently from any number of
 * threads — the underlying queue and size guard are lock-free.
 */
public final class ScratchPool {

    /** Default bound: generous enough that a real workload rarely misses, small enough that it
     *  is not itself a meaningful memory commitment (a few hundred KB at most). */
    public static final int DEFAULT_BOUND = Math.min(Runtime.getRuntime().availableProcessors() * 64, 4096);

    private final ConcurrentLinkedQueue<ConnectionScratch> pool = new ConcurrentLinkedQueue<>();
    private final AtomicInteger size = new AtomicInteger();
    private final int bound;

    public ScratchPool() {
        this(DEFAULT_BOUND);
    }

    public ScratchPool(int bound) {
        this.bound = bound;
    }

    /** Returns a reset, ready-to-use scratch — either reused from the pool or freshly allocated. */
    public ConnectionScratch acquire() {
        ConnectionScratch scratch = pool.poll();
        if (scratch != null) {
            size.decrementAndGet();
            scratch.reset();
            return scratch;
        }
        return new ConnectionScratch();
    }

    /**
     * Returns {@code scratch} to the pool for reuse, unless the pool is already at its bound —
     * in which case it is simply dropped, for the garbage collector, so an unusually large burst
     * of connections cannot grow this cache without limit.
     */
    public void release(ConnectionScratch scratch) {
        if (size.get() >= bound) return;
        size.incrementAndGet();
        pool.offer(scratch);
    }
}
