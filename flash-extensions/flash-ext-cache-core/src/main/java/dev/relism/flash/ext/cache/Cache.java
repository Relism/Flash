package dev.relism.flash.ext.cache;

import java.util.function.Function;

/**
 * A named cache. Obtained from a {@link CacheManager}, safe to hold in a handler field and share
 * across threads.
 *
 * <pre>{@code
 * User user = users.get(id, repo::findById);
 * }</pre>
 *
 * <p>{@link #get} is the only method most code needs. The rest exist for the cases it cannot
 * express: reading without populating, writing a value computed elsewhere, and invalidating.
 *
 * @param <K> key type — must have a stable {@code hashCode}/{@code equals}
 * @param <V> value type
 */
public interface Cache<K, V> {

    /**
     * Returns the cached value, computing and storing it with {@code loader} if absent.
     *
     * <p>The loader runs at most once per key across concurrent callers; the others wait for it
     * rather than each computing their own. A loader returning {@code null} stores nothing and
     * {@code null} is returned.
     */
    V get(K key, Function<? super K, ? extends V> loader);

    /** The cached value, or {@code null} if absent. Never invokes a loader. */
    V getIfPresent(K key);

    /** Stores {@code value}, replacing any existing entry. */
    void put(K key, V value);

    /** Drops {@code key}. Does nothing if it was absent. */
    void invalidate(K key);

    /** Drops every entry. */
    void invalidateAll();

    /** Approximate entry count. Approximate because eviction is asynchronous in most backends. */
    long estimatedSize();

    /**
     * Hit/miss counters since this cache was built, or {@link CacheStats#DISABLED} when the
     * backend was not asked to record them.
     */
    CacheStats stats();
}
