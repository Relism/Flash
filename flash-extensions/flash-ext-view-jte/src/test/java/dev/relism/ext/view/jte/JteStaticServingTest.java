package dev.relism.ext.view.jte;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JteStaticServingTest {

    @Test
    void etag_is_stable_for_same_payload_and_changes_for_different() {
        byte[] a = "hello".getBytes();
        byte[] b = "hello".getBytes();
        byte[] c = "hello!".getBytes();

        String etagA = JteStaticServing.etagFor(a);
        String etagB = JteStaticServing.etagFor(b);
        String etagC = JteStaticServing.etagFor(c);

        assertEquals(etagA, etagB);
        assertNotEquals(etagA, etagC);
    }

    @Test
    void mime_resolution_supports_common_extensions_and_defaults() {
        assertEquals("text/css", JteStaticServing.mimeFor("/sample.css"));
        assertEquals("text/javascript", JteStaticServing.mimeFor("/sample.js"));
        assertEquals("application/wasm", JteStaticServing.mimeFor("/sample.wasm"));
        assertEquals("application/octet-stream", JteStaticServing.mimeFor("/sample.unknown"));
        assertEquals("application/octet-stream", JteStaticServing.mimeFor("/sample"));
    }

    @Test
    void versioned_path_detection_by_filename_hash() {
        assertTrue(JteStaticServing.versionedPath("/app.4f3a2c1b.js"));
        assertTrue(JteStaticServing.versionedPath("/app.abcdefabcdef.js"));
        assertFalse(JteStaticServing.versionedPath("/app.js"));
    }

    @Test
    void range_parsing_supports_basic_cases() {
        assertEquals(10, JteStaticServing.rangeLength("bytes=0-9", 100));
        assertEquals(100, JteStaticServing.rangeLength("bytes=0-", 100));
        assertEquals(10, JteStaticServing.rangeLength("bytes=-10", 100));
        assertEquals(-1, JteStaticServing.rangeLength("bytes=100-120", 100));
        assertEquals(-1, JteStaticServing.rangeLength("invalid", 100));
    }
}
