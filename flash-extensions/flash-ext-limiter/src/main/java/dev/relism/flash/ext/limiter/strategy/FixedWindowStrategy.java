package dev.relism.flash.ext.limiter.strategy;

import dev.relism.flash.ext.limiter.Bucket;
import dev.relism.flash.ext.limiter.LimitConfig;
import dev.relism.flash.ext.limiter.RateLimitStrategy;

/**
 * Fixed-window rate limit: allows up to {@link LimitConfig#limit()} requests per window of
 * {@link LimitConfig#windowMs()} milliseconds. The window is aligned to clock time
 * (e.g. 10:00:00 – 10:00:59 for a 60-second window), not sliding.
 *
 * <h3>Implementation</h3>
 * The entire state fits in a single {@link java.util.concurrent.atomic.AtomicLong}
 * ({@link Bucket#slot0}), packed as:
 * <pre>
 *   high 32 bits = reduced epoch  (currentTimeMs / windowMs) & 0xFFFFFFFFL
 *   low  32 bits = request count in the current window
 * </pre>
 * Each request performs a single CAS loop — no locks, no allocations.
 * At a window boundary the CAS atomically resets the counter to 1.
 *
 * <p>The reduced epoch wraps every {@code 2^32 × windowMs} milliseconds
 * (~13,000 years for a 100 ms window) — collision-free in practice.
 */
public final class FixedWindowStrategy implements RateLimitStrategy {

    @Override
    public long check(Bucket bucket, LimitConfig cfg) {
        long now       = System.currentTimeMillis();
        long absEpoch  = now / cfg.windowMs();
        int  epoch     = (int)(absEpoch & 0xFFFFFFFFL);  // reduced epoch, collision-safe

        while (true) {
            long packed      = bucket.slot0.get();
            int  storedEpoch = (int)(packed >>> 32);
            int  count       = (int)(packed & 0xFFFFFFFFL);

            // Same window: increment; new window: reset to 1.
            // Cap at limit+1 to guard against int overflow on extreme traffic.
            int newCount = (storedEpoch == epoch)
                    ? Math.min(count + 1, cfg.limit() + 1)
                    : 1;

            long newPacked = ((long) epoch << 32) | (newCount & 0xFFFFFFFFL);

            if (bucket.slot0.compareAndSet(packed, newPacked)) {
                return RateLimitStrategy.verdict(newCount <= cfg.limit(), Math.max(0L, cfg.limit() - newCount),
                        (absEpoch + 1) * cfg.windowMs() / 1000L);
            }
            // CAS lost — contention; re-read and retry.
        }
    }
}
