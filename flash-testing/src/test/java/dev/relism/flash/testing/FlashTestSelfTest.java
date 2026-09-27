package dev.relism.flash.testing;

import dev.relism.flash.App;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scenario the harness exists for: two servers in one class, the second configured from
 * the first's address, with a service faked out on the second.
 */
class FlashTestSelfTest {

    private static final AtomicInteger downstreamBoots = new AtomicInteger();

    @RegisterExtension
    static FlashTest upstream = FlashTest.of(() -> App.create()
            .get("/health", (req, res) -> "UP")
            .get("/echo", (req, res) -> "upstream:" + req.query("v")));

    // upstream.baseUri() boots it right here, during this field's initialiser — declaration
    // order does the wiring, with no @Order and no reliance on JUnit's extension ordering.
    @RegisterExtension
    static FlashTest downstream = FlashTest.of(() -> downstream(upstream.baseUri()))
            .replace(Greeter.class, () -> "faked");

    @AfterAll
    static void classScopedServerBootsExactlyOnce() {
        assertEquals(1, downstreamBoots.get(), "a static FlashTest field should boot once per class");
    }

    // ── two servers, wired together ──────────────────────────────────────────

    @Test
    void eachServerGetsItsOwnPort() {
        assertNotEquals(upstream.port(), downstream.port());
        assertTrue(upstream.port() > 0);
    }

    @Test
    void downstreamReachesUpstreamThroughItsInjectedBaseUri() {
        downstream.get("/call-upstream")
                .expectStatus(200)
                .expectBody("UP");
    }

    // ── replacing ────────────────────────────────────────────────────────────

    @Test
    void aReplacementWinsOverAServiceAModuleAdds() {
        downstream.get("/greeting")
                .expectStatus(200)
                .expectBody("faked");
    }

    @Test
    void replacingAfterStartIsRejected() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> downstream.replace(Greeter.class, () -> "too late"));
        assertTrue(error.getMessage().contains("before the server starts"));
    }

    // ── request / response surface ───────────────────────────────────────────

    @Test
    void sendsHeadersQueriesAndBodies() {
        upstream.get("/echo?v=7").expectBody("upstream:7");

        downstream.request()
                .header("X-Trace", "abc")
                .json("{\"name\":\"bob\"}")
                .post("/submit")
                .expectStatus(201)
                .expectHeader("X-Trace", "abc")
                .expectBodyContains("bob");
    }

    @Test
    void failedAssertionsReportTheActualResponse() {
        AssertionError error = assertThrows(AssertionError.class,
                () -> upstream.get("/health").expectStatus(404));

        String message = error.getMessage();
        assertTrue(message.contains("GET /health"), message);
        assertTrue(message.contains("200"), message);
        assertTrue(message.contains("UP"), message);
    }

    @Test
    void theRealServicesAreThereToAssertOn() {
        assertEquals("faked", downstream.services().get(Greeter.class).greet());
    }

    @Test
    void unmatchedRoutesStillComeBackAsResponses() {
        upstream.get("/nope").expectStatus(404);
    }

    // ── fixtures ─────────────────────────────────────────────────────────────

    /** Counts its own builds, so the class-scoped lifecycle can be asserted. */
    static App downstream(URI upstream) {
        downstreamBoots.incrementAndGet();
        return App.create()
                // Added by a module, so the replace(...) above has something real to beat.
                .install(app -> app.add(Greeter.class, () -> "real"))
                .route(HttpMethod.GET, "/greeting", Greeting.class)
                .get("/call-upstream", (req, res) -> {
                    try (HttpClient http = HttpClient.newHttpClient()) {
                        return http.send(HttpRequest.newBuilder(upstream.resolve("/health")).build(),
                                HttpResponse.BodyHandlers.ofString()).body();
                    } catch (IOException failure) {
                        throw new IllegalStateException(failure);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(interrupted);
                    }
                })
                .post("/submit", (req, res) -> res.status(201)
                        .header("X-Trace", req.header("X-Trace"))
                        .body(req.body().bytes()));
    }

    public static final class Greeting implements Handler {
        private final Greeter greeter;
        public Greeting(Greeter greeter) { this.greeter = greeter; }
        @Override public Object handle(Request request, Response response) { return greeter.greet(); }
    }

    @FunctionalInterface
    interface Greeter { String greet(); }
}
