package dev.relism.ext.limiter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe store of pre-allocated {@link Bucket} instances keyed by partition key.
 *
 * <p>On the warm path (key already seen), {@link #get} performs a single
 * {@link ConcurrentHashMap} lookup — no allocation. On the cold path (new key),
 * {@code computeIfAbsent} allocates exactly one {@link Bucket} and inserts it.
 *
 * <p>Buckets accumulate indefinitely; for workloads with unbounded unique keys
 * (e.g. one-shot crawlers), consider periodic store replacement or a bounded
 * LRU map implementation.
 */
public final class BucketStore {

    private final ConcurrentHashMap<String, Bucket> map = new ConcurrentHashMap<>();

    /**
     * Returns the bucket for {@code key}, creating one if absent.
     * Two threads racing on the same new key are guaranteed to receive the same bucket instance.
     */
    public Bucket get(String key) {
        Bucket b = map.get(key);
        return b != null ? b : map.computeIfAbsent(key, k -> new Bucket());
    }
}
