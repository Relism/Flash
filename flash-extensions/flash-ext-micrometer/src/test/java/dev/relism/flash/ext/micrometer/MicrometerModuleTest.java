package dev.relism.flash.ext.micrometer;

import dev.relism.flash.App;
import dev.relism.flash.testing.FlashTest;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class MicrometerModuleTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @RegisterExtension
    final FlashTest app = FlashTest.of(() -> App.create()
            .install(new MicrometerModule(registry))
            .get("/users/{id}", (request, response) -> "ok")
            .any("/files/**", (request, response) -> "any")
            .get("/fail", (request, response) -> { throw new IllegalArgumentException("bad"); })
            .onError((failure, request, response) -> response.status(422).body("invalid")));

    @Test
    void recordsRouteTemplatesFinalErrorsAndFallbacks() {
        app.get("/users/7").expectStatus(200);
        app.get("/fail").expectStatus(422);
        app.get("/missing").expectStatus(404);

        assertCount("/users/{id}", "200", "SUCCESS");
        assertCount("/fail", "422", "CLIENT_ERROR");
        assertCount("NOT_FOUND", "404", "CLIENT_ERROR");
        assertNull(registry.find("http.server.requests").tag("uri", "/users/7").timer());
    }

    /** A method outside the enum is one series, {@code _OTHER}, however many tokens clients invent. */
    @Test
    void aRouteForEveryMethodTagsTheStandardOnesAndOneOtherForTheRest() {
        app.get("/files/a").expectStatus(200);
        for (String method : new String[] {"PROPFIND", "MKCOL", "INVENTED"}) app.request().send(method, "/files/a").expectStatus(200);

        assertCount("GET", "/files/**", "200", "SUCCESS", 1);
        assertCount("_OTHER", "/files/**", "200", "SUCCESS", 3);
    }

    private void assertCount(String uri, String status, String outcome) {
        assertCount("GET", uri, status, outcome, 1);
    }

    private void assertCount(String method, String uri, String status, String outcome, int count) {
        Timer timer = registry.find("http.server.requests")
                .tags("method", method, "uri", uri, "status", status, "outcome", outcome)
                .timer();
        assertNotNull(timer);
        assertEquals(count, timer.count());
    }
}
