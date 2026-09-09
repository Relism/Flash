package dev.relism.flash.testing;

import java.net.http.HttpHeaders;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A response from a {@link FlashTest} server, with chainable assertions.
 *
 * <pre>{@code
 * app.get("/api/users")
 *    .expectStatus(200)
 *    .expectHeader("content-type", "application/json")
 *    .expectBodyContains("alice");
 * }</pre>
 *
 * Every failure message carries the request line, the status and the body, so a red test says
 * what actually came back rather than only what did not match. Use {@link #status()},
 * {@link #body()} and {@link #headers()} for anything the assertions do not cover.
 */
public final class FlashResponse {

    private final String method;
    private final String path;
    private final HttpResponse<String> response;

    FlashResponse(String method, String path, HttpResponse<String> response) {
        this.method = method;
        this.path = path;
        this.response = response;
    }

    // ── Raw access ───────────────────────────────────────────────────────────

    /** Response status code. */
    public int status() { return response.statusCode(); }

    /** Response body decoded as a string. */
    public String body() { return response.body(); }

    /** All response headers. */
    public HttpHeaders headers() { return response.headers(); }

    /** First value of {@code name} (case-insensitive), or {@code null} if absent. */
    public String header(String name) { return response.headers().firstValue(name).orElse(null); }

    // ── Assertions ───────────────────────────────────────────────────────────

    /** Asserts the status code. */
    public FlashResponse expectStatus(int expected) {
        assertEquals(expected, status(), this::describe);
        return this;
    }

    /** Asserts the first value of a header (name case-insensitive). */
    public FlashResponse expectHeader(String name, String expected) {
        assertEquals(expected, header(name), () -> "header '" + name + '\'' + describe());
        return this;
    }

    /** Asserts the body matches exactly. */
    public FlashResponse expectBody(String expected) {
        assertEquals(expected, body(), this::describe);
        return this;
    }

    /** Asserts the body contains {@code fragment}. */
    public FlashResponse expectBodyContains(String fragment) {
        assertTrue(body().contains(fragment), () -> "expected body to contain '" + fragment + '\'' + describe());
        return this;
    }

    private String describe() {
        return "\n  request: " + method + ' ' + path
             + "\n  status:  " + status()
             + "\n  body:    " + body();
    }
}
