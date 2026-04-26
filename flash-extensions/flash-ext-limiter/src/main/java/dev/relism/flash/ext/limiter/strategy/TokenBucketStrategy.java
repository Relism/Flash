package dev.relism.flash.ext.limiter.strategy;

import dev.relism.flash.ext.limiter.Bucket;
import dev.relism.flash.ext.limiter.LimitConfig;
import dev.relism.flash.ext.limiter.RateLimitStrategy;

/**
 * Token-bucket rate limit: tokens refill continuously at a rate of
 * {@code limit / windowMs} tokens per millisecond, up to a maximum of {@code limit} tokens.
 * Each request consumes one token. Burst traffic is absorbed until the bucket empties.
 *
 * <h3>Implementation</h3>
 * <ul>
 *   <li>{@link Bucket#slot0} — current token count scaled by {@value #SCALE}
 *       (allows sub-token precision without floating-point). Starts at 0; treated as
 *       {@code maxScaled} when {@link Bucket#slot1} is 0 (first call → bucket starts full).</li>
 *   <li>{@link Bucket#slot1} — last-refill timestamp in ms. 0 = not yet initialised.</li>
 * </ul>
 *
 * <p>Each request CAS-loops on {@code slot0}; {@code slot1} is advanced monotonically via CAS
 * after a successful token consumption — never regresses to an older timestamp under concurrent load.
 */
public final class TokenBucketStrategy implements RateLimitStrategy {

    /** Fixed-point scale factor. Stored tokens = actual tokens × SCALE. */
    static final long SCALE = 1_000L;

    @Override
    public boolean check(Bucket bucket, LimitConfig cfg, long[] out) {
        long now       = System.currentTimeMillis();
        long maxScaled = (long) cfg.limit() * SCALE;
        // Refill rate: limit tokens per windowMs → (limit * SCALE) / windowMs scaled-tokens per ms.
        // Minimum 1 to ensure progress even for very large windows.
        long rfPerMs   = Math.max(1L, maxScaled / cfg.windowMs());

        while (true) {
            long lastMs    = bucket.slot1.get();
            long rawTokens = bucket.slot0.get();

            // When slot1 == 0 the bucket has never been used: treat as one full window elapsed
            // so the bucket starts completely full.
            long elapsed       = (lastMs == 0L) ? cfg.windowMs() : Math.max(0L, now - lastMs);
            long currentTokens = Math.min(maxScaled, rawTokens + elapsed * rfPerMs);

            if (currentTokens < SCALE) {
                // Not enough for one token — compute when the next token arrives.
                long needed   = SCALE - currentTokens;
                long msToNext = (needed + rfPerMs - 1) / rfPerMs; // ceiling division
                out[0] = 0L;
                out[1] = (now + msToNext) / 1000L;
                // Best-effort: advance the refill baseline so the next call gets a fresh elapsed.
                bucket.slot0.compareAndSet(rawTokens, currentTokens);
                bucket.slot1.compareAndSet(lastMs, now);
                return false;
            }

            long newTokens = currentTokens - SCALE;
            if (bucket.slot0.compareAndSet(rawTokens, newTokens)) {
                // Advance refill baseline: CAS ensures we never regress to an older timestamp.
                if (lastMs < now) bucket.slot1.compareAndSet(lastMs, now);
                out[0] = newTokens / SCALE;
                out[1] = now / 1000L;
                return true;
            }
            // CAS lost — another thread consumed a token concurrently; re-read and retry.
        }
    }
}
