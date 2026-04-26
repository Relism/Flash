package dev.relism.flash.ext.limiter;

import dev.relism.flash.ext.limiter.strategy.FixedWindowStrategy;
import dev.relism.flash.ext.limiter.strategy.SlidingWindowStrategy;
import dev.relism.flash.ext.limiter.strategy.TokenBucketStrategy;

/**
 * Contract for a rate-limit algorithm. Implementations must be:
 * <ul>
 *   <li><b>Lock-free</b> — rely only on {@link java.util.concurrent.atomic.AtomicLong} CAS operations.</li>
 *   <li><b>Stateless</b> — all mutable state lives in the {@link Bucket}; the strategy itself
 *       holds no instance fields so the same object can be shared across threads and rules.</li>
 * </ul>
 *
 * <p>Called on every request — must not allocate on the hot path.
 *
 * @see FixedWindowStrategy
 * @see SlidingWindowStrategy
 * @see TokenBucketStrategy
 */
public interface RateLimitStrategy {

    /**
     * Checks whether this request is within the limit and updates the bucket atomically.
     *
     * <p>On return, {@code out} contains:
     * <ul>
     *   <li>{@code out[0]} — remaining allowed requests in the current window (≥ 0).</li>
     *   <li>{@code out[1]} — Unix epoch seconds at which the quota resets (for {@code X-RateLimit-Reset}
     *       and {@code Retry-After} headers).</li>
     * </ul>
     *
     * @param bucket  per-key state carrier (pre-allocated, never null)
     * @param cfg     immutable rule configuration
     * @param out     caller-supplied two-element array; values are overwritten on every call
     * @return {@code true} if the request is within the limit and should proceed
     */
    boolean check(Bucket bucket, LimitConfig cfg, long[] out);
}
