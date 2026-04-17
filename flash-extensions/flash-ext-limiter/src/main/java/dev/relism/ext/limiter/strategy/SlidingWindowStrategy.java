package dev.relism.ext.limiter.strategy;

import dev.relism.ext.limiter.Bucket;
import dev.relism.ext.limiter.LimitConfig;
import dev.relism.ext.limiter.RateLimitStrategy;

/**
 * Sliding-window counter rate limit: approximates a true sliding window by interpolating
 * between the previous fixed window's count and the current window's count.
 *
 * <pre>
 *   estimate = prevCount × (1 − elapsed / windowMs) + currentCount
 * </pre>
 *
 * <p>This is the same approximation used by Redis. It eliminates the boundary burst
 * problem of {@link FixedWindowStrategy} while staying O(1) memory and lock-free.
 * The error is bounded: in the worst case the true rate at the boundary can exceed
 * the limit by at most {@code limit × (1 − elapsed/windowMs)} — typically a few percent.
 *
 * <h3>Slot layout</h3>
 * <ul>
 *   <li>{@link Bucket#slot0} — packed {@code (reducedEpoch << 32 | currentCount)}</li>
 *   <li>{@link Bucket#slot1} — count from the immediately preceding epoch (0 = none)</li>
 * </ul>
 *
 * <p>On a window transition the thread that wins the {@code slot0} CAS also writes
 * {@code slot1}. A concurrent thread that reads {@code slot0} after the transition but
 * before {@code slot1} is written sees a slightly stale previous count — acceptable for
 * an approximation algorithm.
 */
public final class SlidingWindowStrategy implements RateLimitStrategy {

    @Override
    public boolean check(Bucket bucket, LimitConfig cfg, long[] out) {
        long now      = System.currentTimeMillis();
        long windowMs = cfg.windowMs();
        long absEpoch = now / windowMs;
        int  epoch    = (int)(absEpoch & 0xFFFFFFFFL);
        long elapsed  = now % windowMs;   // ms elapsed inside the current window

        while (true) {
            long packed      = bucket.slot0.get();
            int  storedEpoch = (int)(packed >>> 32);
            int  count       = (int)(packed & 0xFFFFFFFFL);

            if (storedEpoch == epoch) {
                // ── Same window ──────────────────────────────────────────────────
                long prevCount = bucket.slot1.get();
                // Integer interpolation — no floating-point on hot path.
                long estimate  = (prevCount * (windowMs - elapsed)) / windowMs + count + 1;

                if (estimate > cfg.limit()) {
                    out[0] = 0L;
                    out[1] = (absEpoch + 1) * windowMs / 1000L;
                    return false;
                }

                int  newCount  = Math.min(count + 1, cfg.limit() + 1);
                long newPacked = ((long) epoch << 32) | (newCount & 0xFFFFFFFFL);
                if (!bucket.slot0.compareAndSet(packed, newPacked)) continue; // CAS lost, retry

                out[0] = Math.max(0L, cfg.limit() - estimate);
                out[1] = (absEpoch + 1) * windowMs / 1000L;
                return true;

            } else {
                // ── Window transition ────────────────────────────────────────────
                // If the stored epoch is exactly the one before ours, carry its count forward.
                // If it's older (gap ≥ 2 windows), the previous window is effectively empty.
                int  prevEpoch = (int)((absEpoch - 1) & 0xFFFFFFFFL);
                long oldCount  = (storedEpoch == prevEpoch) ? count : 0L;

                long newPacked = ((long) epoch << 32) | 1L;
                if (!bucket.slot0.compareAndSet(packed, newPacked)) continue; // CAS lost, retry

                // Won the transition: publish old count so the same-window branch can read it.
                bucket.slot1.set(oldCount);

                long estimate = (oldCount * (windowMs - elapsed)) / windowMs + 1;
                out[0] = Math.max(0L, cfg.limit() - estimate);
                out[1] = (absEpoch + 1) * windowMs / 1000L;
                return estimate <= cfg.limit();
            }
        }
    }
}
