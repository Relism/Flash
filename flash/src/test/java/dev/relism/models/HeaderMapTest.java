package dev.relism.models;

import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeaderMapTest {

    // --- helpers ---

    private static HeaderMap parse(String raw, String... headers) {
        StringBuilder sb = new StringBuilder();
        for (String h : headers) sb.append(h).append("\r\n");
        byte[] buffer = sb.toString().getBytes(StandardCharsets.UTF_8);
        HeaderMap map = new HeaderMap(buffer);
        
        int current = 0;
        for (int i = 0; i < headers.length; i++) {
            int colon = sb.indexOf(":", current);
            int lineEnd = sb.indexOf("\r\n", current);
            int valueStart = colon + 1;
            while (valueStart < lineEnd && buffer[valueStart] == ' ') valueStart++;
            
            map.add(current, colon - current, valueStart, lineEnd - valueStart);
            current = lineEnd + 2;
        }
        return map;
    }

    // --- getFirst ---

    @Test
    void getFirst_existingHeader() {
        HeaderMap map = parse("", "Host: localhost", "Accept: text/plain");
        assertEquals("localhost", map.getFirst("Host"));
        assertEquals("text/plain", map.getFirst("Accept"));
    }

    @Test
    void getFirst_caseInsensitive() {
        HeaderMap map = parse("", "ConteNT-tYPe: application/json");
        assertEquals("application/json", map.getFirst("content-type"));
        assertEquals("application/json", map.getFirst("CONTENT-TYPE"));
    }

    @Test
    void getFirst_missingHeader_returnsNull() {
        HeaderMap map = parse("", "Host: localhost");
        assertNull(map.getFirst("Accept"));
    }

    // --- getAll ---

    @Test
    void getAll_multipleValuesByName() {
        HeaderMap map = parse("", "Cookie: a=1", "Set-Cookie: token=123", "Cookie: b=2");
        assertEquals(List.of("a=1", "b=2"), map.getAll("Cookie"));
    }

    @Test
    void getAll_missingHeader_returnsEmptyList() {
        HeaderMap map = parse("", "Host: localhost");
        assertTrue(map.getAll("Cookie").isEmpty());
    }

    @Test
    void getAll_returnsAllHeaders() {
        HeaderMap map = parse("", "A: 1", "B: 2");
        assertEquals(List.of("1", "2"), map.getAll());
    }

    // --- getView ---

    @Test
    void getView_returnsZeroCopyView() {
        HeaderMap map = parse("", "Host: localhost");
        ByteView view = map.getView("Host");
        assertNotNull(view);
        assertEquals(9, view.length());
        assertEquals('l', view.byteAt(0));
        assertEquals('t', view.byteAt(8));
    }

    @Test
    void getView_missingHeader_returnsNull() {
        HeaderMap map = parse("", "Host: localhost");
        assertNull(map.getView("Accept"));
    }
    
    // --- limit ---
    
    @Test
    void maxHeadersLimit() {
        byte[] buffer = new byte[100];
        HeaderMap map = new HeaderMap(buffer);
        for (int i = 0; i < 32; i++) {
            map.add(0, 1, 1, 1);
        }
        assertThrows(IllegalStateException.class, () -> map.add(0, 1, 1, 1));
    }
}
