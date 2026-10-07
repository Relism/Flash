package dev.relism.flash.ext.limiter;

import dev.relism.flash.App;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LimiterModuleTest {

    @Limit(requests = 2, window = 1, windowUnit = TimeUnit.HOURS)
    public static final class Limited extends RequestHandler {
        @Override protected Object handle(Request req, Response res) { return "ok"; }
    }

    static final LimiterModule limiter = new LimiterModule().resolver("everyone", req -> "all");

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
            .install(limiter, new OpenApiModule("/openapi", "Limits", "1.0.0"))
            .route(HttpMethod.GET, "/limited", Limited.class)
            .get("/manual", (req, res) -> "ok", limiter.limit("everyone", 1, 1, TimeUnit.HOURS))
            .get("/plain", (req, res) -> "ok"));

    @Test
    void annotatedHandler_countsDownThenRefuses() {
        app.get("/limited").expectStatus(200).expectHeader("X-RateLimit-Limit", "2").expectHeader("X-RateLimit-Remaining", "1");
        app.get("/limited").expectStatus(200).expectHeader("X-RateLimit-Remaining", "0");
        FlashResponse refused = app.get("/limited").expectStatus(429).expectHeader("X-RateLimit-Remaining", "0");
        long retryAfter = Long.parseLong(refused.header("Retry-After"));
        assertTrue(retryAfter >= 1 && retryAfter <= 3600, "Retry-After " + retryAfter);
        assertEquals(0, Long.parseLong(refused.header("X-RateLimit-Reset")) % 3600, "a fixed window resets on the hour");
    }

    @Test
    void middleware_limitsALambdaRoute_andLeavesOthersAlone() {
        app.get("/manual").expectStatus(200);
        app.get("/manual").expectStatus(429);
        assertEquals(null, app.get("/plain").expectStatus(200).header("X-RateLimit-Limit"));
    }

    @Test
    void unknownKey_failsAtBoot() {
        assertThrows(IllegalStateException.class, () -> new LimiterModule().limit("user", 1, 1, TimeUnit.SECONDS));
    }

    @Test
    void document_showsTheHeadersAndThe429OfLimitedOperationsOnly() {
        String doc = app.get("/openapi.json").expectStatus(200).body();
        assertTrue(doc.contains("\"429\""), doc);
        assertTrue(doc.contains("Retry-After"), doc);
        assertTrue(doc.contains("X-RateLimit-Remaining"), doc);
        assertTrue(new LimiterModule.Docs().operationFor(String.class).isEmpty());
    }
}
