package dev.relism.flash.ext.cache;

import java.time.Duration;

/**
 * How one cache should behave. Every field is optional — a spec that sets nothing gives an
 * unbounded cache that never expires, which is a memory leak wearing a hat, so set at least one
 * of {@link #maxSize} or {@link #ttl}.
 *
 * <pre>{@code
 * CacheSpec.of().maxSize(10_000).ttl(Duration.ofMinutes(10))
 * }</pre>
 *
 * <p>Mutable builder rather than a record with {@code withX} copies: it is constructed once at
 * boot inside a lambda and never shared.
 */
public final class CacheSpec {

    private long maxSize = -1;
    private Duration ttl;
    private Duration ttlAfterAccess;
    private boolean recordStats;

    private CacheSpec() {}

    public static CacheSpec of() {
        return new CacheSpec();
    }

    /** Maximum entries before the backend starts evicting. Negative means unbounded. */
    public CacheSpec maxSize(long maxSize) {
        this.maxSize = maxSize;
        return this;
    }

    /** Entries expire this long after they were written. */
    public CacheSpec ttl(Duration ttl) {
        this.ttl = ttl;
        return this;
    }

    /** Entries expire this long after they were last read or written. */
    public CacheSpec ttlAfterAccess(Duration ttlAfterAccess) {
        this.ttlAfterAccess = ttlAfterAccess;
        return this;
    }

    /**
     * Records hit/miss counters for {@link Cache#stats()}.
     *
     * <p>Off by default: counting costs a pair of atomic increments on every lookup, and a cache
     * nobody is measuring should not pay for numbers nobody reads.
     */
    public CacheSpec recordStats() {
        this.recordStats = true;
        return this;
    }

    public long maxSize()            { return maxSize; }
    public Duration ttl()            { return ttl; }
    public Duration ttlAfterAccess() { return ttlAfterAccess; }
    public boolean statsRecorded()   { return recordStats; }
    public boolean bounded()         { return maxSize >= 0; }
}
