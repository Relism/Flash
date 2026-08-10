package dev.relism.flash;

import dev.relism.flash.models.Request;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class RequestParserTest {

    // --- helpers ---

    private static Request parse(String raw) throws IOException {
        byte[] bytes = raw.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        return new RequestParser().parse(new ByteArrayInputStream(bytes));
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
        Request r   = new RequestParser().parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(body, new String(r.body().bytes(), StandardCharsets.UTF_8));
    }

    @Test
    void body_parsed_forQueryMethod() throws IOException {
        String body = "{\"filter\":\"active\"}";
        String raw  = "QUERY / HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        Request r   = new RequestParser().parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
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
        assertNull(new RequestParser().parse(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void missingHeaderTerminator_throwsIOException() {
        // Valid request line but stream ends before \r\n\r\n
        byte[] raw = "GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> new RequestParser().parse(new ByteArrayInputStream(raw)));
    }

    @Test
    void unknownHttpMethod_throwsIOException() {
        assertThrows(IOException.class, () -> parse(req("BREW /coffee HTTP/1.1", "Host: localhost")));
    }

    @Test
    void requestLine_noProtocol_throwsIOException() {
        // No space after path, parser cannot find protocol boundary
        assertThrows(IOException.class, () -> parse(req("GET /noproto")));
    }

    @Test
    void headers_exceedingMaxBufferSize_throwsIOException() {
        // Feed more bytes than the configured cap with no \r\n\r\n : must throw
        int cap = 16 * 1024;
        byte[] giant = new byte[cap + 1];
        Arrays.fill(giant, (byte) 'A');
        assertThrows(IOException.class, () -> new RequestParser(cap).parse(new ByteArrayInputStream(giant)));
    }

    @Test
    void chunkedTransferEncoding_bodyReadable() throws IOException {
        String raw = "POST / HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "Transfer-Encoding: chunked\r\n" +
                     "\r\n" +
                     "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        Request r = new RequestParser().parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(-1L, r.body().contentLength()); // -1 = chunked
        assertArrayEquals("hello world".getBytes(StandardCharsets.UTF_8), r.body().bytes());
    }

    @Test
    void contentLength_parsedAsLong() throws IOException {
        // 5 GB — too large to materialize, but contentLength must be a long
        String raw = "POST / HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "Content-Length: 5000000000\r\n" +
                     "\r\n";
        Request r = new RequestParser().parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(5_000_000_000L, r.body().contentLength());
        assertThrows(IllegalStateException.class, r.body()::bytes);
    }

    @Test
    void contentLength_largerThanBody_readsPartial() throws IOException {
        // Content-Length claims 50 but stream ends after 5 bytes
        String body = "hello";
        String raw  = "POST / HTTP/1.1\r\nContent-Length: 50\r\n\r\n" + body;
        Request r   = new RequestParser().parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(50, r.body().bytes().length);
        assertEquals(body, new String(r.body().bytes(), 0, body.length(), StandardCharsets.UTF_8));
    }
}
