package dev.relism.flash.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpMethodTest {

    // --- helpers ---

    private static HttpMethod parse(String raw) {
        byte[] b = raw.getBytes(StandardCharsets.UTF_8);
        return HttpMethod.fromBytes(b, 0, b.length);
    }
    
    private static HttpMethod parse(String raw, int off, int len) {
        byte[] b = raw.getBytes(StandardCharsets.UTF_8);
        return HttpMethod.fromBytes(b, off, len);
    }

    // --- fromBytes ---

    @Test
    void fromBytes_validMethods() {
        assertEquals(HttpMethod.GET,     parse("GET"));
        assertEquals(HttpMethod.POST,    parse("POST"));
        assertEquals(HttpMethod.PUT,     parse("PUT"));
        assertEquals(HttpMethod.DELETE,  parse("DELETE"));
        assertEquals(HttpMethod.PATCH,   parse("PATCH"));
        assertEquals(HttpMethod.OPTIONS, parse("OPTIONS"));
        assertEquals(HttpMethod.HEAD,    parse("HEAD"));
        assertEquals(HttpMethod.TRACE,   parse("TRACE"));
        assertEquals(HttpMethod.CONNECT, parse("CONNECT"));
        assertEquals(HttpMethod.PURGE,   parse("PURGE"));
    }

    @Test
    void fromBytes_withOffsetAndLength() {
        assertEquals(HttpMethod.POST, parse("XXXPOSTYYY", 3, 4));
        assertEquals(HttpMethod.GET,  parse(" GET ", 1, 3));
    }

    @Test
    void fromBytes_invalidMethods_returnsNull() {
        assertNull(parse("INVALID"));
        assertNull(parse("GE"));      // Too short
        assertNull(parse("GETT"));    // Too long
        assertNull(parse("posT"));    // Case sensitive
        assertNull(parse(""));        // Empty
    }
    
    // --- bytes ---
    
    @Test
    void getBytes_matchesName() {
        assertArrayEquals("GET".getBytes(StandardCharsets.UTF_8), HttpMethod.GET.getBytes());
        assertArrayEquals("POST".getBytes(StandardCharsets.UTF_8), HttpMethod.POST.getBytes());
    }
    
    // --- toString ---
    
    @Test
    void toString_matchesName() {
        assertEquals("GET", HttpMethod.GET.toString());
        assertEquals("DELETE", HttpMethod.DELETE.toString());
    }
}
