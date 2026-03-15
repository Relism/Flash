package dev.relism;

import dev.relism.models.Request;
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
        return RequestParser.parse(new ByteArrayInputStream(bytes));
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
        assertEquals("flash", r.getQueryParam("q"));
        assertEquals("2",     r.getQueryParam("page"));
    }

    // --- headers ---

    @Test
    void headers_parsed() throws IOException {
        Request r = parse(req("GET / HTTP/1.1", "Host: example.com", "Accept: application/json"));
        assertEquals("example.com",       r.getHeader("Host"));
        assertEquals("application/json",  r.getHeader("Accept"));
    }

    @Test
    void headers_caseInsensitive() throws IOException {
        Request r = parse(req("GET / HTTP/1.1", "Content-Type: text/plain"));
        assertEquals("text/plain", r.getHeader("content-type"));
        assertEquals("text/plain", r.getHeader("CONTENT-TYPE"));
    }

    // --- body ---

    @Test
    void body_parsed() throws IOException {
        String body = "hello body";
        String raw  = "POST / HTTP/1.1\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        Request r   = RequestParser.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(body, new String(r.getBody(), StandardCharsets.UTF_8));
    }

    @Test
    void body_emptyWhenNoContentLength() throws IOException {
        Request r = parse(req("GET / HTTP/1.1", "Host: localhost"));
        assertEquals(0, r.getBody().length);
    }

    // --- edge cases / robustness ---

    @Test
    void emptyInputStream_returnsNull() throws IOException {
        assertNull(RequestParser.parse(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void missingHeaderTerminator_throwsIOException() {
        // Valid request line but stream ends before \r\n\r\n
        byte[] raw = "GET / HTTP/1.1\r\nHost: localhost\r\n".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class, () -> RequestParser.parse(new ByteArrayInputStream(raw)));
    }

    @Test
    void unknownHttpMethod_throwsIOException() {
        assertThrows(IOException.class, () -> parse(req("BREW /coffee HTTP/1.1", "Host: localhost")));
    }

    @Test
    void requestLine_noProtocol_throwsIOException() {
        // No space after path — parser cannot find protocol boundary
        assertThrows(IOException.class, () -> parse(req("GET /noproto")));
    }

    @Test
    void headersOverBufferSize_throwsIOException() {
        // 9 KB of data with no \r\n\r\n exhausts the 8 KB buffer
        byte[] giant = new byte[9000];
        Arrays.fill(giant, (byte) 'A');
        assertThrows(IOException.class, () -> RequestParser.parse(new ByteArrayInputStream(giant)));
    }

    @Test
    void contentLength_largerThanBody_readsPartial() throws IOException {
        // Content-Length claims 50 but stream ends after 5 bytes
        String body = "hello";
        String raw  = "POST / HTTP/1.1\r\nContent-Length: 50\r\n\r\n" + body;
        Request r   = RequestParser.parse(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
        assertNotNull(r);
        assertEquals(50, r.getBody().length);
        assertEquals(body, new String(r.getBody(), 0, body.length(), StandardCharsets.UTF_8));
    }
}
