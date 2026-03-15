package dev.relism.models;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.HttpMethod;
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
    void request_creationAndGetters() {
        HeaderMap headers = new HeaderMap(new byte[0]);
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/path"), viewOf("q=1"), viewOf("HTTP/1.1"), headers);
        byte[] body = "body".getBytes(StandardCharsets.UTF_8);
        
        Request r = new Request(line, body);
        
        assertEquals(line, r.getRequestLine());
        assertArrayEquals(body, r.getBody());
        assertNotNull(r.toString());
    }

    // --- delegates ---

    @Test
    void headers_delegatesToRequestLine() {
        byte[] buffer = "Host: localhost\r\n".getBytes(StandardCharsets.UTF_8);
        HeaderMap headers = new HeaderMap(buffer);
        headers.add(0, 4, 6, 9);
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), null, viewOf("HTTP/1.1"), headers);
        Request r = new Request(line, new byte[0]);
        
        assertEquals("localhost", r.getHeader("Host"));
        assertEquals(List.of("localhost"), r.getHeaders("Host"));
        assertEquals(List.of("localhost"), r.getHeaders());
    }

    // --- pathParams ---

    @Test
    void pathParam_lazyGet() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), null, viewOf("HTTP/1.1"), new HeaderMap(new byte[0]));
        Request r = new Request(line, new byte[0]);
        
        assertNull(r.getPathParam("id"));
        
        r.setPathParams(new PathParams(viewOf("/123"), new String[]{"id"}, new int[]{1}, new int[]{3}));
        assertEquals("123", r.getPathParam("id"));
    }

    // --- queryParams ---

    @Test
    void queryParam_lazyGet_fromQueryString() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), viewOf("a=1&b=2&b=3"), viewOf("HTTP/1.1"), new HeaderMap(new byte[0]));
        Request r = new Request(line, new byte[0]);
        
        assertEquals("1", r.getQueryParam("a"));
        assertEquals("2", r.getQueryParam("b")); // First value
        assertEquals(List.of("2", "3"), r.getQueryParams("b"));
    }
    
    @Test
    void queryParam_lazyGet_nullQueryString() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/"), null, viewOf("HTTP/1.1"), new HeaderMap(new byte[0]));
        Request r = new Request(line, new byte[0]);
        
        assertNull(r.getQueryParam("a"));
        assertTrue(r.getQueryParams("a").isEmpty());
    }
}
