package dev.relism.flash.ext.cache.caffeine;

import com.github.benmanes.caffeine.cache.Caffeine;
import dev.relism.flash.ext.cache.Cache;
import dev.relism.flash.ext.cache.CacheManager;
import dev.relism.flash.ext.cache.CacheSpec;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** In-process {@link CacheManager} backed by Caffeine. */
final class CaffeineCacheManager implements CacheManager {

    private final Map<String, Entry> caches = new ConcurrentHashMap<>();

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> build(String name, java.util.function.Consumer<CacheSpec> configure) {
        CacheSpec spec = CacheSpec.of();
        configure.accept(spec);

        Entry entry = caches.computeIfAbsent(name, key -> new Entry(describe(spec), create(spec)));
        // Two handlers sharing a cache is the point; two handlers disagreeing about its size or
        // TTL is a bug that would otherwise resolve to whichever one ran first.
        String requested = describe(spec);
        if (!entry.signature.equals(requested))
            throw new IllegalStateException("Cache '" + name + "' already exists as " + entry.signature
                    + " but was requested as " + requested);
        return (Cache<K, V>) entry.cache;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <K, V> Cache<K, V> cache(String name) {
        Entry entry = caches.get(name);
        return entry == null ? null : (Cache<K, V>) entry.cache;
    }

    @Override
    public Set<String> names() {
        return Set.copyOf(caches.keySet());
    }

    /** Releases every entry so a stopped app does not keep its values alive. */
    void clear() {
        caches.values().forEach(entry -> entry.cache.invalidateAll());
        caches.clear();
    }

    private static CaffeineCache<Object, Object> create(CacheSpec spec) {
        Caffeine<Object, Object> builder = Caffeine.newBuilder();
        if (spec.bounded()) builder.maximumSize(spec.maxSize());
        if (spec.ttl() != null) builder.expireAfterWrite(spec.ttl());
        if (spec.ttlAfterAccess() != null) builder.expireAfterAccess(spec.ttlAfterAccess());
        if (spec.statsRecorded()) builder.recordStats();
        return new CaffeineCache<>(builder.build(), spec.statsRecorded());
    }

    private static String describe(CacheSpec spec) {
        return "maxSize=" + spec.maxSize() + " ttl=" + spec.ttl()
                + " ttlAfterAccess=" + spec.ttlAfterAccess() + " stats=" + spec.statsRecorded();
    }

    private record Entry(String signature, CaffeineCache<Object, Object> cache) {}
}
