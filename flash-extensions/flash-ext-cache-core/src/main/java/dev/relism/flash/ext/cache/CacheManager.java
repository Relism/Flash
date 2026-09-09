package dev.relism.flash.ext.cache;

import java.util.function.Consumer;

/**
 * Creates and hands back named caches. Resolve it with {@code require(CacheManager.class)}; a
 * backend extension such as {@code flash-ext-cache-caffeine} provides the implementation.
 *
 * <pre>{@code
 * @Override protected void onInit() {
 *     users = require(CacheManager.class).build("users", spec -> spec
 *             .maxSize(10_000)
 *             .ttl(Duration.ofMinutes(10)));
 * }
 * }</pre>
 *
 * <p>{@link #build} is idempotent per name: calling it twice returns the same cache rather than
 * two, so several handlers can share one without coordinating who creates it. The spec of the
 * first call wins; a later call with a different spec is a configuration mistake and throws.
 */
public interface CacheManager {

    /** Creates the named cache, or returns the existing one. */
    <K, V> Cache<K, V> build(String name, Consumer<CacheSpec> spec);

    /** The named cache, or {@code null} if {@link #build} has not been called for it. */
    <K, V> Cache<K, V> cache(String name);

    /** Every cache name built so far, for an ops endpoint. */
    java.util.Set<String> names();
}
