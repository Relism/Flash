package dev.relism.flash.ext.cache.caffeine;

import dev.relism.flash.ext.cache.Cache;
import dev.relism.flash.ext.cache.CacheManager;
import dev.relism.flash.ext.cache.CacheStats;
import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CaffeineCacheTest {

    private static final AtomicInteger loads = new AtomicInteger();

    @RegisterExtension
    static FlashTest app = FlashTest.of(configured -> {
        configured.install(new CaffeineCacheExtension());
        configured.ctx().onReady(() -> {
            Cache<String, String> users = configured.ctx().require(CacheManager.class)
                    .build("users", spec -> spec.maxSize(100).ttl(Duration.ofMinutes(5)).recordStats());
            configured.get("/users/{id}", (req, res) ->
                    users.get(req.param("id"), id -> "loaded:" + id + ":" + loads.incrementAndGet()));
        });
    });

    private static CacheManager manager() {
        return app.app().ctx().require(CacheManager.class);
    }

    @Test
    void aRepeatedRequestIsServedFromCache() {
        String first = app.get("/users/alice").expectStatus(200).body();
        String second = app.get("/users/alice").expectStatus(200).body();

        assertEquals(first, second);
        assertTrue(first.startsWith("loaded:alice:"));
    }

    @Test
    void distinctKeysLoadSeparately() {
        assertNotEquals(app.get("/users/bob").body(), app.get("/users/carol").body());
    }

    @Test
    void statsCountHitsAndMisses() {
        Cache<String, String> cache = manager().build("stats-probe", spec -> spec.maxSize(10).recordStats());
        cache.get("k", key -> "v");
        cache.get("k", key -> "v");

        CacheStats stats = cache.stats();
        assertEquals(1, stats.misses());
        assertEquals(1, stats.hits());
        assertEquals(0.5, stats.hitRate());
    }

    @Test
    void statsAreDisabledUnlessAskedFor() {
        Cache<String, String> cache = manager().build("no-stats", spec -> spec.maxSize(10));
        cache.get("k", key -> "v");

        assertEquals(CacheStats.DISABLED, cache.stats());
    }

    @Test
    void theLoaderRunsOncePerKeyUnderConcurrency() throws Exception {
        Cache<String, String> cache = manager().build("single-flight", spec -> spec.maxSize(10));
        AtomicInteger invocations = new AtomicInteger();
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            Thread.ofVirtual().start(() -> {
                try {
                    start.await();
                    cache.get("hot", key -> {
                        invocations.incrementAndGet();
                        return "value";
                    });
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));

        assertEquals(1, invocations.get(), "concurrent callers must share one load, not race");
    }

    @Test
    void aNullLoaderResultStoresNothing() {
        Cache<String, String> cache = manager().build("nulls", spec -> spec.maxSize(10));

        assertNull(cache.get("missing", key -> null));
        assertNull(cache.getIfPresent("missing"));
    }

    @Test
    void invalidateDropsOneKeyAndInvalidateAllDropsEverything() {
        Cache<String, String> cache = manager().build("invalidation", spec -> spec.maxSize(10));
        cache.put("a", "1");
        cache.put("b", "2");

        cache.invalidate("a");
        assertNull(cache.getIfPresent("a"));
        assertEquals("2", cache.getIfPresent("b"));

        cache.invalidateAll();
        assertNull(cache.getIfPresent("b"));
    }

    @Test
    void buildIsIdempotentPerName() {
        Cache<String, String> first = manager().build("shared", spec -> spec.maxSize(10));
        Cache<String, String> second = manager().build("shared", spec -> spec.maxSize(10));

        assertSame(first, second, "two handlers asking for one cache must get one cache");
        assertSame(first, manager().cache("shared"));
    }

    @Test
    void disagreeingOnASharedCacheIsARejectedMistakeNotASilentWinner() {
        manager().build("contested", spec -> spec.maxSize(10));

        IllegalStateException conflict = assertThrows(IllegalStateException.class,
                () -> manager().build("contested", spec -> spec.maxSize(999)));
        assertTrue(conflict.getMessage().contains("contested"), conflict.getMessage());
    }

    @Test
    void unknownNameReturnsNullRatherThanBuildingOne() {
        assertNull(manager().cache("never-built"));
    }

    /** Why CaffeineCacheExtension registers onClose: values must not outlive the app holding them. */
    @Test
    void stoppingTheAppReleasesEveryCache() {
        FlashApp standalone = FlashApp.create(FlashConfiguration.builder()
                .port(0).host("127.0.0.1").shutdownDrainTimeoutMs(250).build())
                .install(new CaffeineCacheExtension());
        standalone.start();
        CacheManager manager = standalone.ctx().require(CacheManager.class);
        manager.build("scoped", spec -> spec.maxSize(10)).put("k", "v");
        assertEquals(1, manager.names().size());

        standalone.stop().join();

        assertTrue(manager.names().isEmpty(), "caches must be released when the app stops");
    }
}
