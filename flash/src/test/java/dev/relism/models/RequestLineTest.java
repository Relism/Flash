package dev.relism.models;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RequestLineTest {

    // --- helpers ---

    private static ByteView viewOf(String s) {
        if (s == null) return null;
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new ByteView() {
            public int length() { return bytes.length; }
            public byte byteAt(int idx) { return bytes[idx]; }
        };
    }

    // --- initialization ---

    @Test
    void constructionAndGetters() {
        ByteView path = viewOf("/api");
        ByteView query = viewOf("q=1");
        ByteView proto = viewOf("HTTP/1.1");
        HeaderMap headers = new HeaderMap(new byte[0]);
        
        RequestLine rl = new RequestLine(HttpMethod.GET, path, query, proto, headers);
        
        assertEquals(HttpMethod.GET, rl.getMethod());
        assertEquals(path, rl.getPath());
        assertEquals(query, rl.getQuery());
        assertEquals(proto, rl.getProtocol());
        assertEquals(headers, rl.getHeaders());
        assertNotNull(rl.toString());
    }
}
