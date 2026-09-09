package dev.relism.flash.ext.cache;

/**
 * Hit/miss counters for one cache.
 *
 * @param hits      lookups that found a value
 * @param misses    lookups that had to load
 * @param evictions entries dropped to respect {@link CacheSpec#maxSize()}
 */
public record CacheStats(long hits, long misses, long evictions) {

    /** Returned when {@link CacheSpec#recordStats()} was not set — all zero, and says so. */
    public static final CacheStats DISABLED = new CacheStats(0, 0, 0);

    /** Hits divided by lookups, or 0 when nothing has been looked up yet. */
    public double hitRate() {
        long total = hits + misses;
        return total == 0 ? 0 : (double) hits / total;
    }
}
