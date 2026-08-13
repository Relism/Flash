package dev.relism.flash.models;

import dev.relism.fpr.core.ByteView;
import dev.relism.flash.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RequestTest {

    // --- helpers ---

    private static ByteView viewOf(String s) {
        if (s == null) return null;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new ByteView() {
            public int length() { return bytes.length; }
            public byte byteAt(int idx) { return bytes[idx]; }
        };
    }

    // --- creation ---

    @Test
    void request_creationAndAccessors() {
        Http1HeaderMap headers = new Http1HeaderMap();
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/path"), viewOf("q=1"), viewOf("HTTP/1.1"), headers);
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);

        Request r = new Request(line, body);

        assertEquals(HttpMethod.GET, r.method());
        assertEquals("/path", r.path());
        assertArrayEquals(body, r.body().bytes());
        assertNotNull(r.toString());
    }

    // --- headers ---

    @Test
    void header_delegatesToRequestLine() {
        byte[] buffer = "Host: localhost\r\n".getBytes(StandardCharsets.UTF_8);
        Http1HeaderMap headers = new Http1HeaderMap();
        headers.reset(buffer, 0, buffer.length);
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), null, viewOf("HTTP/1.1"), headers);
        Request r = new Request(line, new byte[0]);

        assertEquals("localhost", r.header("Host"));
        assertEquals(List.of("localhost"), r.headers("Host"));
        assertEquals(List.of("localhost"), r.headers());
    }

    // --- path params ---

    @Test
    void param_lazyGet() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), null, viewOf("HTTP/1.1"), new Http1HeaderMap());
        Request r = new Request(line, new byte[0]);

        assertNull(r.param("id"));

        r.setPathParams(new PathParams(viewOf("/123"), new String[]{"id"}, new int[]{1}, new int[]{3}));
        assertEquals("123", r.param("id"));
    }

    // --- query params ---

    @Test
    void query_lazyGet_fromQueryString() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), viewOf("a=1&b=2&b=3"), viewOf("HTTP/1.1"), new Http1HeaderMap());
        Request r = new Request(line, new byte[0]);

        assertEquals("1", r.query("a"));
        assertEquals("2", r.query("b"));
        assertEquals(List.of("2", "3"), r.queries("b"));
    }

    @Test
    void query_lazyGet_nullQueryString() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), null, viewOf("HTTP/1.1"), new Http1HeaderMap());
        Request r = new Request(line, new byte[0]);

        assertNull(r.query("a"));
        assertTrue(r.queries("a").isEmpty());
    }
}
