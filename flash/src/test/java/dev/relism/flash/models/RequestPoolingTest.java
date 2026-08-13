package dev.relism.flash.models;

import dev.relism.flash.http.HttpMethod;
import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code EX-22}: {@link Request} is pooled <em>per connection</em> (one instance owned by
 * {@code RequestParser}, repositioned via {@link Request#forParsed} for every request on that
 * connection) — not via a shared cross-connection pool. The plan's own safety-check wording
 * ("connection A's {@code Authorization} header must never be visible on connection B") describes
 * a threat model that does not structurally apply to this design: two different connections
 * never share a {@code Request} instance at all (each owns its own {@code RequestParser}, hence
 * its own {@code Request}) — see {@code DECISIONS.md} for the pooling-granularity decision this
 * follows from. The real, applicable threat this class actually tests: request <em>N+1</em> on
 * the *same* keep-alive connection must never see stale data left over from request <em>N</em>,
 * since those two requests genuinely do share one {@code Request} instance.
 */
class RequestPoolingTest {

    private static ByteView viewOf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new ByteView() {
            public int length() { return bytes.length; }
            public byte byteAt(int idx) { return bytes[idx]; }
        };
    }

    private static Http1HeaderMap headersOf(String... rawLines) {
        StringBuilder sb = new StringBuilder();
        for (String line : rawLines) sb.append(line).append("\r\n");
        byte[] buf = sb.toString().getBytes(StandardCharsets.UTF_8);
        Http1HeaderMap map = new Http1HeaderMap();
        map.reset(buf, 0, buf.length);
        return map;
    }

    @Test
    void forParsed_reusesTheSamePooledInstance_neverAllocatesANewOne() {
        Request pooled = new Request();
        RequestLine line1 = new RequestLine(HttpMethod.GET, viewOf("/a"), null, viewOf("HTTP/1.1"), headersOf());
        Request r1 = Request.forParsed(pooled, line1, RequestBody.empty(), null, null);
        assertSame(pooled, r1);

        RequestLine line2 = new RequestLine(HttpMethod.POST, viewOf("/b"), null, viewOf("HTTP/1.1"), headersOf());
        Request r2 = Request.forParsed(pooled, line2, RequestBody.empty(), null, null);
        assertSame(pooled, r2);
        assertSame(r1, r2, "the same pooled instance must be returned for every request on one connection");
    }

    @Test
    void secondRequest_onSameConnection_doesNotSeeFirstRequestsAuthorizationHeader() {
        Request pooled = new Request();

        RequestLine first = new RequestLine(HttpMethod.GET, viewOf("/secure"), null, viewOf("HTTP/1.1"),
                headersOf("Authorization: Bearer super-secret-token-A"));
        Request r1 = Request.forParsed(pooled, first, RequestBody.empty(), null, null);
        assertEquals("Bearer super-secret-token-A", r1.header("Authorization"));

        // A second request on the same keep-alive connection, with no Authorization header at all.
        RequestLine second = new RequestLine(HttpMethod.GET, viewOf("/public"), null, viewOf("HTTP/1.1"),
                headersOf("Host: example.com"));
        Request r2 = Request.forParsed(pooled, second, RequestBody.empty(), null, null);

        assertNull(r2.header("Authorization"), "the second request must not see the first request's Authorization header");
        assertNull(r2.header("authorization"));
        for (String value : r2.headers()) {
            assertFalse(value.contains("super-secret-token-A"), "leaked secret found in: " + value);
        }
    }

    @Test
    void secondRequest_doesNotSeeFirstRequestsPathParams() {
        Request pooled = new Request();
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/users/123"), null, viewOf("HTTP/1.1"), headersOf());
        Request r1 = Request.forParsed(pooled, line, RequestBody.empty(), null, null);
        PathParams.inject(r1, new PathParams(viewOf("/users/123"), new String[]{"id"}, new int[]{7}, new int[]{3}));
        assertEquals("123", r1.param("id"));

        RequestLine line2 = new RequestLine(HttpMethod.GET, viewOf("/health"), null, viewOf("HTTP/1.1"), headersOf());
        Request r2 = Request.forParsed(pooled, line2, RequestBody.empty(), null, null);
        assertNull(r2.param("id"), "path params from the previous request on this connection must not leak");
        assertNull(r2.getPathParams());
    }

    @Test
    void secondRequest_doesNotSeeFirstRequestsCachedPathOrQueryParams() {
        Request pooled = new Request();
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/first"), viewOf("token=abc"), viewOf("HTTP/1.1"), headersOf());
        Request r1 = Request.forParsed(pooled, line, RequestBody.empty(), null, null);
        assertEquals("/first", r1.path());
        assertEquals("abc", r1.query("token"));

        RequestLine line2 = new RequestLine(HttpMethod.GET, viewOf("/second"), null, viewOf("HTTP/1.1"), headersOf());
        Request r2 = Request.forParsed(pooled, line2, RequestBody.empty(), null, null);
        assertEquals("/second", r2.path(), "cachedPath from the previous request must not leak");
        assertNull(r2.query("token"), "query params from the previous request must not leak");
    }
}
