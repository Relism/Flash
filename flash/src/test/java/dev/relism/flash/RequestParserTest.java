package dev.relism.flash;

import dev.relism.flash.exceptions.MalformedRequestException;
import dev.relism.flash.models.Request;
import dev.relism.flash.transport.BufferedByteSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RequestParserTest {

    // --- helpers ---

    // BufferedByteSource's Socket reference is only touched when a deadline is set — none of
    // these tests set one, so `null` is safe here.
    private static BufferedByteSource source(byte[] bytes) {
        return new BufferedByteSource(new ByteArrayInputStream(bytes), null);
    }

    private static Request parse(String raw) throws IOException {
        byte[] bytes = raw.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        return new RequestParser().parse(source(bytes));
    }

    private static String req(String requestLine, String... headers) {
        StringBuilder sb = new StringBuilder(requestLine).append("\n");
        for (String h : headers) sb.append(h).append("\n");
        return sb.append("\n").toString();
    }

    // --- request line ---

    @Test
    void path_withoutQueryString() throws IOException {
        Request r = parse(req("GET /hello HTTP/1.1", "Host: localhost"));
        assertEquals("/hello", r.getRequestLine().getPath().toString());
        assertNull(r.getRequestLine().getQuery());
    }

    @Test
    void path_splitsAtQuestionMark() throws IOException {
        Request r = parse(req("GET /hello?foo=bar&baz=qux HTTP/1.1", "Host: localhost"));
        assertEquals("/hello",       r.getRequestLine().getPath().toString());
        assertEquals("foo=bar&baz=qux", r.getRequestLine().getQuery().toString());
    }

    @Test
    void queryParam_resolvedFromPath() throws IOException {
        Request r = parse(req("GET /search?q=flash&page=2 HTTP/1.1", "Host: localhost"));
        assertEquals("flash", r.query("q"));
        assertEquals("2",     r.query("page"));
    }


    @Test
    void samePooledParser_secondRequestWithoutQuery_doesNotLeakFirstRequestsQuery() throws IOException {
        RequestParser parser = new RequestParser();
        byte[] first = req("GET /search?token=super-secret HTTP/1.1", "Host: a").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Request r1 = parser.parse(source(first));
        assertEquals("token=super-secret", r1.getRequestLine().getQuery().toString());

        byte[] second = req("GET /health HTTP/1.1", "Host: a").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        Request r2 = parser.parse(source(second));
        assertNull(r2.getRequestLine().getQuery(), "the second request must not see the first request's leftover query view");
        assertEquals("/health", r2.getRequestLine().getPath().toString());
    }

    @Test
    void samePooledParser_secondRequest_seesOnlyItsOwnPathAndProtocol() throws IOException {
        RequestParser parser = new RequestParser();
        Request r1 = parser.parse(source(req("GET /first HTTP/1.1", "Host: a").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals("/first", r1.getRequestLine().getPath().toString());

        Request r2 = parser.parse(source(req("POST /second HTTP/1.0", "Host: a").replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8)));
        assertEquals("/second", r2.getRequestLine().getPath().toString());
        assertEquals("HTTP/1.0", r2.getRequestLine().getProtocol().toString());
    }

    // --- headers ---

    @Test
    void headers_parsed() throws IOException {
        Request r = parse(req("GET / HTTP/1.1", "Host: example.com", "Accept: application/json"));
        assertEquals("example.com",       r.header("Host"));
        assertEquals("application/json",  r.header("Accept"));
    }

    @Test
    void headers_caseInsensitive() throws IOException {
        Request r = parse(req("GET / HTTP/1.1", "Content-Type: text/plain"));
        assertEquals("text/plain", r.header("content-type"));
        assertEquals("text/plain", r.header("CONTENT-TYPE"));
    }

    // --- body ---

    @Test
    void body_parsed() throws IOException {
        String body = "hello body";
        String raw  = "POST / HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        Request r   = new RequestParser().parse(source(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(body, new String(r.body().bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void body_parsed_forQueryMethod() throws IOException {
        String body = "{\"filter\":\"active\"}";
        String raw  = "QUERY / HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        Request r   = new RequestParser().parse(source(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(dev.relism.flash.http.HttpMethod.QUERY, r.method());
        assertEquals(body, new String(r.body().bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void body_emptyWhenNoContentLength() throws IOException {
        Request r = parse(req("GET / HTTP/1.1", "Host: localhost"));
        assertTrue(r.body().isEmpty());
    }

    // --- edge cases / robustness ---

    @Test
    void emptyInputStream_returnsNull() throws IOException {
        assertNull(new RequestParser().parse(source(new byte[0])));
    }

    @Test
    void missingHeaderTerminator_throwsMalformedRequestException() {
        // Valid request line but stream ends before \r\n\r\n. Previously a generic IOException;
        byte[] raw = "GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(MalformedRequestException.class, () -> new RequestParser().parse(source(raw)));
    }

    @Test
    void unknownHttpMethod_throwsMalformedRequestException() {
        // RFC 9110 §9.1 SHOULD 501 an unrecognised method.
        MalformedRequestException e = assertThrows(MalformedRequestException.class,
                () -> parse(req("BREW /coffee HTTP/1.1", "Host: localhost")));
        assertEquals(501, e.status());
    }

    @Test
    void requestLine_noProtocol_throwsMalformedRequestException() {
        // No space after path, parser cannot find protocol boundary
        MalformedRequestException e = assertThrows(MalformedRequestException.class,
                () -> parse(req("GET /noproto")));
        assertEquals(400, e.status());
    }

    @Test
    void headers_exceedingMaxBufferSize_throwsMalformedRequestException() {
        int cap = 16 * 1024;
        byte[] giant = new byte[cap + 1];
        Arrays.fill(giant, (byte) 'A');
        MalformedRequestException e = assertThrows(MalformedRequestException.class,
                () -> new RequestParser(cap).parse(source(giant)));
        assertEquals(431, e.status());
    }

    @Test
    void chunkedTransferEncoding_bodyReadable() throws IOException {
        String raw = "POST / HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "Transfer-Encoding: chunked\r\n" +
                     "\r\n" +
                     "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        Request r = new RequestParser().parse(source(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(-1L, r.body().contentLength()); // -1 = chunked
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), r.body().bytes());
    }

    @Test
    void transferEncoding_multiValueEndingInChunked_recognised() throws IOException {
        // old whole-value comparison misclassified this as not chunked at all.
        String raw = "POST / HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "Transfer-Encoding: gzip, chunked\r\n" +
                     "\r\n" +
                     "2\r\nhi\r\n0\r\n\r\n";
        Request r = new RequestParser().parse(source(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(-1L, r.body().contentLength());
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), r.body().bytes());
    }

    @Test
    void contentLength_parsedAsLong() throws IOException {
        // ~3 GB — comfortably above Integer.MAX_VALUE (proving the value is a genuine long, not
        // silently truncated) while staying within Http1Limits.MAX_CONTENT_LENGTH (4 GiB).
        String raw = "POST / HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "Content-Length: 3000000000\r\n" +
                     "\r\n";
        Request r = new RequestParser().parse(source(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(3_000_000_000L, r.body().contentLength());
        assertThrows(IllegalStateException.class, r.body()::bytes);
    }

    @Test
    void contentLength_largerThanBody_readsPartial() throws IOException {
        // Content-Length claims 50 but stream ends after 5 bytes
        String body = "hello";
        String raw  = "POST / HTTP/1.1\r\nContent-Length: 50\r\n\r\n" + body;
        Request r   = new RequestParser().parse(source(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(50, r.body().bytes().length);
        assertEquals(body, new String(r.body().bytes(), 0, body.length(), StandardCharsets.UTF_8));
    }
}
