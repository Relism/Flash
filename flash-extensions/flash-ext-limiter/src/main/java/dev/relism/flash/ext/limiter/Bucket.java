package dev.relism.flash.ext.limiter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One key's rate-limit state: two {@link AtomicLong} slots whose meaning is the strategy's —
 * fixed and sliding windows pack {@code (epoch << 32 | count)} in {@code slot0} (sliding keeps the
 * previous window's count in {@code slot1}); the token bucket keeps tokens × 1000 and the last
 * refill time. {@code seenMs} is when the key was last counted, for the store to evict idle keys.
 */
public final class Bucket {
    public final AtomicLong slot0 = new AtomicLong(0L);
    public final AtomicLong slot1 = new AtomicLong(0L);
    volatile long seenMs;
}
