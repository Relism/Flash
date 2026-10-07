package dev.relism.flash.ext.cache.caffeine;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.ext.cache.Cache;
import dev.relism.flash.ext.cache.CacheManager;
import dev.relism.flash.ext.cache.CacheStats;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CaffeineCacheTest {

    private static final AtomicInteger loads = new AtomicInteger();

    /** Builds its cache once, from the manager it is given. */
    public static final class Users extends RequestHandler {
        private final Cache<String, String> users;

        public Users(CacheManager caches) {
            users = caches.build("users", spec -> spec.maxSize(100).ttl(Duration.ofMinutes(5)).recordStats());
        }

        @Override
        protected Object handle(Request req, Response res) {
            return users.get(req.param("id"), id -> "loaded:" + id + ":" + loads.incrementAndGet());
        }
    }

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(new CaffeineCacheModule())
            .route(HttpMethod.GET, "/users/{id}", Users.class));

    private static CacheManager manager() {
        return app.services().get(CacheManager.class);
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

    /** Why the manager is closed with the app: values must not outlive the app holding them. */
    @Test
    void stoppingTheAppReleasesEveryCache() {
        App standalone = App.create().install(new CaffeineCacheModule());
        Server server = Server.start(standalone, ServerConfig.builder()
                .port(0).host("127.0.0.1").shutdownDrainTimeoutMs(250).build());
        CacheManager manager = standalone.services().get(CacheManager.class);
        manager.build("scoped", spec -> spec.maxSize(10)).put("k", "v");
        assertEquals(1, manager.names().size());

        server.stop().join();

        assertTrue(manager.names().isEmpty(), "caches must be released when the app stops");
    }
}
