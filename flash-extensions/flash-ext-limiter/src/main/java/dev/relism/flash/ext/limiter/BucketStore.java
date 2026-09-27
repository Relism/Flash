package dev.relism.flash.ext.limiter;

import java.util.concurrent.ConcurrentHashMap;

/**
 * One rule's buckets, by key, bounded. A known key is one map lookup; a new key allocates its
 * bucket once. At {@code maxKeys}, keys idle for a whole window are dropped; if they are all still
 * active, a new key shares one overflow bucket, so a flood of invented keys meets the limit rather
 * than the heap.
 */
final class BucketStore {

    private final ConcurrentHashMap<String, Bucket> map = new ConcurrentHashMap<>();
    private final Bucket overflow = new Bucket();
    private final int maxKeys;
    private final long windowMs;

    BucketStore(int maxKeys, long windowMs) {
        this.maxKeys = maxKeys;
        this.windowMs = windowMs;
    }

    Bucket get(String key, long now) {
        Bucket bucket = map.get(key);
        if (bucket == null) {
            if (map.size() >= maxKeys) {
                map.values().removeIf(idle -> idle.seenMs < now - windowMs);
                if (map.size() >= maxKeys) return overflow;
            }
            bucket = map.computeIfAbsent(key, ignored -> new Bucket());
        }
        bucket.seenMs = now;
        return bucket;
    }
}
