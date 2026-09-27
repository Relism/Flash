package dev.relism.flash.http;

import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QueryParamsTest {

    // --- helpers ---

    private static QueryParams of(String raw) {
        byte[] bytes = raw.getBytes(StandardCharsets.UTF_8);
        ByteView view = new ByteView() {
            public int  length()        { return bytes.length; }
            public byte byteAt(int idx) { return bytes[idx]; }
        };
        return new QueryParams(view);
    }

    // --- get ---

    @Test
    void get_singleParam() {
        assertEquals("hello", of("name=hello").get("name"));
    }

    @Test
    void get_firstOfMultiple() {
        assertEquals("1", of("a=1&b=2&c=3").get("a"));
        assertEquals("2", of("a=1&b=2&c=3").get("b"));
        assertEquals("3", of("a=1&b=2&c=3").get("c"));
    }

    @Test
    void get_missingKey_returnsNull() {
        assertNull(of("a=1&b=2").get("z"));
    }

    @Test
    void get_emptyValue() {
        assertEquals("", of("key=").get("key"));
    }

    @Test
    void get_multiValue_returnsFirst() {
        assertEquals("a", of("tag=a&tag=b&tag=c").get("tag"));
    }

    // --- getAll ---

    @Test
    void getAll_multiValue() {
        assertEquals(List.of("a", "b", "c"), of("tag=a&tag=b&tag=c").getAll("tag"));
    }

    @Test
    void getAll_missingKey_returnsEmpty() {
        assertTrue(of("a=1").getAll("z").isEmpty());
    }

    // --- EMPTY ---

    @Test
    void empty_returnsNull() {
        assertNull(QueryParams.EMPTY.get("anything"));
        assertTrue(QueryParams.EMPTY.getAll("anything").isEmpty());
    }
}
