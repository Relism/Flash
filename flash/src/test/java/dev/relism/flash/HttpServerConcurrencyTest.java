package dev.relism.flash;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerConcurrencyTest {

    private static App app;

    private static Server server;
    private static int port;
    private static HttpClient httpClient;

    @BeforeAll
    static void setUp() {
        ServerConfig config1 = ServerConfig.builder()
                .port(0)
                .host("127.0.0.1")
                .build();
        app = App.create();

        app.get("/ping", (req, res) -> "pong");
        app.post("/echo", (req, res) -> {
            byte[] body = req.body().bytes();
            return new Response(200, body, ContentType.TEXT_PLAIN);
        });

        server = Server.start(app, config1);
        port = server.port();

        httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    @AfterAll
    static void tearDown() {
        if (server != null) server.stop().join();
    }

    // --- helpers ---

    private HttpResponse<String> get(int targetPort, String path) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + targetPort + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(port, path);
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return httpClient.send(
                HttpRequest.newBuilder()
                        .uri(URI.create("http://127.0.0.1:" + port + path))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    // --- concurrency ---

    @Test
    void concurrent_getRequests_allReturn200() throws Exception {
        int count = 20;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        List<String> responses = new CopyOnWriteArrayList<>();

        for (int i = 0; i < count; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    HttpResponse<String> res = get("/ping");
                    responses.add(res.statusCode() + "|" + res.body());
                    if (res.statusCode() == 200 && "pong".equals(res.body()))
                        successes.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
        assertEquals(count, successes.get(), () -> "Responses: " + responses);
    }

    @Test
    void concurrent_mixedRoutes_allReturn200() throws Exception {
        int perMethod = 10;
        int total = perMethod * 2;
        ExecutorService pool = Executors.newFixedThreadPool(total);
        CountDownLatch ready = new CountDownLatch(total);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger getSuccesses = new AtomicInteger();
        AtomicInteger postSuccesses = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < perMethod; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    HttpResponse<String> res = get("/ping");
                    if (res.statusCode() == 200 && "pong".equals(res.body()))
                        getSuccesses.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }
        for (int i = 0; i < perMethod; i++) {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    HttpResponse<String> res = post("/echo", "hello");
                    if (res.statusCode() == 200 && "hello".equals(res.body()))
                        postSuccesses.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

        assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
        assertEquals(perMethod, getSuccesses.get());
        assertEquals(perMethod, postSuccesses.get());
    }

    /**
     * Races the router's lazy-compile step: a fresh app with 10 registered routes
     * is hit by threads simultaneously before any request has been processed,
     * causing multiple threads to compete on the first compilation.
     */
    @Test
    void concurrent_lazyCompile_noRaceCondition() throws Exception {
        // Deliberately a brand-new app: this test is about compiling routes lazily on first
        // use, so it must not share the class-scoped server.
        ServerConfig config2 = ServerConfig.builder()
                .port(0)
                .host("127.0.0.1")
                .build();
        App freshApp = App.create();

        for (int i = 0; i < 10; i++) {
            final int idx = i;
            freshApp.get("/route" + idx, (req, res) -> "handler" + idx);
        }

        Server server = Server.start(freshApp, config2);
        int freshPort = server.port();

        int count = 20;
        ExecutorService pool = Executors.newFixedThreadPool(count);
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger successes = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();

        for (int i = 0; i < count; i++) {
            final int routeIdx = i % 10;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    HttpResponse<String> res = get(freshPort, "/route" + routeIdx);
                    if (res.statusCode() == 200 && ("handler" + routeIdx).equals(res.body()))
                        successes.incrementAndGet();
                } catch (Exception e) {
                    errors.add(e);
                }
            });
        }

        ready.await();
        start.countDown();
        pool.shutdown();

        try {
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
            assertTrue(errors.isEmpty(), () -> "Unexpected errors: " + errors);
            assertEquals(count, successes.get());
        } finally {
            server.stop();
        }
    }
}
