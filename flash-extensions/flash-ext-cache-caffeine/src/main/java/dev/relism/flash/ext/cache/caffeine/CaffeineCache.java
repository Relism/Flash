package dev.relism.flash.ext.cache.caffeine;

import dev.relism.flash.ext.cache.Cache;
import dev.relism.flash.ext.cache.CacheStats;

import java.util.function.Function;

/**
 * {@link Cache} over a Caffeine cache. A thin adapter by design: every method delegates directly,
 * adding no wrapper object, no copy and no synchronisation of its own.
 */
final class CaffeineCache<K, V> implements Cache<K, V> {

    private final com.github.benmanes.caffeine.cache.Cache<K, V> delegate;
    private final boolean statsRecorded;

    CaffeineCache(com.github.benmanes.caffeine.cache.Cache<K, V> delegate, boolean statsRecorded) {
        this.delegate = delegate;
        this.statsRecorded = statsRecorded;
    }

    @Override
    public V get(K key, Function<? super K, ? extends V> loader) {
        // Caffeine's own get(key, mappingFunction) already guarantees the loader runs once per key
        // across concurrent callers; wrapping it in anything of ours would only add a race.
        return delegate.get(key, loader);
    }

    @Override public V getIfPresent(K key)      { return delegate.getIfPresent(key); }
    @Override public void put(K key, V value)   { delegate.put(key, value); }
    @Override public void invalidate(K key)     { delegate.invalidate(key); }
    @Override public void invalidateAll()       { delegate.invalidateAll(); }
    @Override public long estimatedSize()       { return delegate.estimatedSize(); }

    @Override
    public CacheStats stats() {
        if (!statsRecorded) return CacheStats.DISABLED;
        com.github.benmanes.caffeine.cache.stats.CacheStats snapshot = delegate.stats();
        return new CacheStats(snapshot.hitCount(), snapshot.missCount(), snapshot.evictionCount());
    }
}
