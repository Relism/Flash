package dev.relism.flash.models;

import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HeaderMapTest {

    // --- helpers ---

    private static HeaderMap parse(String... headers) {
        StringBuilder sb = new StringBuilder();
        for (String h : headers) sb.append(h).append("\r\n");
        byte[] buffer = sb.toString().getBytes(StandardCharsets.UTF_8);
        HeaderMap map = new HeaderMap();
        map.reset(buffer, 0, buffer.length);
        return map;
    }

    // --- first ---

    @Test
    void first_existingHeader() {
        HeaderMap map = parse("Host: localhost", "Accept: text/plain");
        assertEquals("localhost", map.first("Host"));
        assertEquals("text/plain", map.first("Accept"));
    }

    @Test
    void first_caseInsensitive() {
        HeaderMap map = parse("ConteNT-tYPe: application/json");
        assertEquals("application/json", map.first("content-type"));
        assertEquals("application/json", map.first("CONTENT-TYPE"));
    }

    @Test
    void first_missingHeader_returnsNull() {
        HeaderMap map = parse("Host: localhost");
        assertNull(map.first("Accept"));
    }

    // --- all ---

    @Test
    void all_multipleValuesByName() {
        HeaderMap map = parse("Cookie: a=1", "Set-Cookie: token=123", "Cookie: b=2");
        assertEquals(List.of("a=1", "b=2"), map.all("Cookie"));
    }

    @Test
    void all_missingHeader_returnsEmptyList() {
        HeaderMap map = parse("Host: localhost");
        assertTrue(map.all("Cookie").isEmpty());
    }

    @Test
    void all_returnsAllHeaders() {
        HeaderMap map = parse("A: 1", "B: 2");
        assertEquals(List.of("1", "2"), map.all());
    }

    // --- view ---

    @Test
    void view_returnsZeroCopyView() {
        HeaderMap map = parse("Host: localhost");
        ByteView view = map.view("Host");
        assertNotNull(view);
        assertEquals(9, view.length());
        assertEquals('l', view.byteAt(0));
        assertEquals('t', view.byteAt(8));
    }

    @Test
    void view_missingHeader_returnsNull() {
        HeaderMap map = parse("Host: localhost");
        assertNull(map.view("Accept"));
    }

    // --- empty ---

    @Test
    void emptyMap_returnsNullAndEmptyList() {
        HeaderMap map = new HeaderMap();
        assertNull(map.first("Host"));
        assertTrue(map.all("Host").isEmpty());
        assertTrue(map.all().isEmpty());
    }

    // --- forEach ---

    @Test
    void forEach_visitsEveryHeaderInDeclarationOrder() {
        HeaderMap map = parse("Host: localhost", "Accept: text/plain", "Cookie: a=1");
        List<String> seen = new java.util.ArrayList<>();
        map.forEach((name, value) -> seen.add(toStr(name) + "=" + toStr(value)));
        assertEquals(List.of("Host=localhost", "Accept=text/plain", "Cookie=a=1"), seen);
    }

    @Test
    void forEach_emptyMap_neverInvokesConsumer() {
        HeaderMap map = new HeaderMap();
        map.forEach((name, value) -> fail("must not be called on an empty map"));
    }

    @Test
    void forEach_reusesTheSameTwoViewInstancesAcrossEveryHeader() {
        // The zero-allocation contract: forEach must reposition two ByteViews in place, not
        // allocate a fresh pair per header — same instances across all three calls here.
        HeaderMap map = parse("A: 1", "B: 2", "C: 3");
        List<ByteView> names = new java.util.ArrayList<>();
        List<ByteView> values = new java.util.ArrayList<>();
        map.forEach((name, value) -> { names.add(name); values.add(value); });

        assertSame(names.get(0), names.get(1));
        assertSame(names.get(1), names.get(2));
        assertSame(values.get(0), values.get(1));
        assertSame(values.get(1), values.get(2));
    }

    private static String toStr(ByteView v) {
        byte[] b = new byte[v.length()];
        for (int i = 0; i < b.length; i++) b[i] = v.byteAt(i);
        return new String(b, StandardCharsets.UTF_8);
    }
}
