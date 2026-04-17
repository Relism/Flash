package dev.relism.ext.limiter;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Pre-allocated per-key rate limit state. Holds two {@link AtomicLong} slots whose
 * semantics are strategy-specific:
 *
 * <ul>
 *   <li><b>FIXED_WINDOW</b>: {@code slot0} = packed {@code (epoch << 32 | count)},
 *       {@code slot1} unused.</li>
 *   <li><b>SLIDING_WINDOW</b>: {@code slot0} = packed {@code (epoch << 32 | count)},
 *       {@code slot1} = request count from the immediately preceding epoch.</li>
 *   <li><b>TOKEN_BUCKET</b>: {@code slot0} = tokens × 1000 (scaled),
 *       {@code slot1} = last-refill timestamp (ms since epoch).</li>
 * </ul>
 *
 * <p>Buckets are created once per unique key (via {@link BucketStore}) and reused
 * for the lifetime of the server — zero allocation on the warm path.
 */
public final class Bucket {
    public final AtomicLong slot0 = new AtomicLong(0L);
    public final AtomicLong slot1 = new AtomicLong(0L);
}
