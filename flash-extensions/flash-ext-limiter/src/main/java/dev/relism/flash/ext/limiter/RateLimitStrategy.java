package dev.relism.flash.ext.limiter;

/**
 * A rate-limit algorithm: lock-free (only {@code AtomicLong} CAS on the {@link Bucket}), stateless
 * (every mutable value lives in the bucket) and allocation-free, since it runs on every request.
 * The verdict comes back packed in one {@code long}: see {@link #verdict}.
 */
public interface RateLimitStrategy {

    /** Counts this request against {@code bucket} and returns the packed verdict. */
    long check(Bucket bucket, LimitConfig cfg);

    /** Allowed or not, the requests left, and the epoch second the quota resets at, in one {@code long}. */
    static long verdict(boolean allowed, long remaining, long resetEpochSeconds) {
        return (allowed ? 0L : Long.MIN_VALUE) | (Math.min(remaining, Integer.MAX_VALUE) << 32) | (resetEpochSeconds & 0xFFFFFFFFL);
    }

    static boolean allowed(long verdict) {
        return verdict >= 0;
    }

    static long remaining(long verdict) {
        return (verdict >>> 32) & Integer.MAX_VALUE;
    }

    static long reset(long verdict) {
        return verdict & 0xFFFFFFFFL;
    }
}
