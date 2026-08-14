package dev.relism.flash.models;

import dev.relism.flash.http.ContentType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ResponsePoolingTest {

    @Test
    void reset_returnsSameInstanceAndClearsPreviousState() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.header("X-Trace", "abc123").status(201).body("first body");
        assertEquals(1, r.getHeaders().size());

        Response reset = r.reset(200, ContentType.JSON);
        assertSame(r, reset, "reset() must reposition the same instance, not allocate a new one");
        assertEquals(200, reset.getStatusCode());
        assertNull(reset.getBody(), "body from the previous cycle must not leak");
        assertTrue(reset.getHeaders().isEmpty(), "headers from the previous cycle must not leak");
        assertArrayEquals(ContentType.JSON.getBytes(), reset.getContentType());
    }

    @Test
    void secondCycle_doesNotSeeFirstCyclesCustomHeader() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.header("X-Secret", "leaked-if-broken");
        assertEquals(1, r.getHeaders().size());

        r.reset(200, ContentType.TEXT_PLAIN);
        r.header("X-Public", "fine");

        assertEquals(1, r.getHeaders().size());
        String only = new String(r.getHeaders().get(0));
        assertTrue(only.contains("X-Public"));
        assertFalse(only.contains("X-Secret"), "stale header from the previous cycle leaked: " + only);
    }

    @Test
    void secondCycle_reusesHeaderRegionAcrossManyHeaders_staysCorrect() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        for (int cycle = 0; cycle < 5; cycle++) {
            r.reset(200, ContentType.TEXT_PLAIN);
            for (int i = 0; i < 10; i++) {
                r.header("X-Cycle" + cycle + "-H" + i, "v" + i);
            }
            assertEquals(10, r.getHeaders().size(), "cycle " + cycle);
            String last = new String(r.getHeaders().get(9));
            assertTrue(last.contains("X-Cycle" + cycle + "-H9: v9"), "cycle " + cycle + ": " + last);
        }
    }

    @Test
    void mixedStructuredAndRawHeaders_preserveInsertionOrder() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        r.header("A", "1");
        r.header("B-raw: 2\r\n".getBytes());
        r.header("C", "3");

        var headers = r.getHeaders();
        assertEquals(3, headers.size());
        assertEquals("A: 1\r\n", new String(headers.get(0)));
        assertEquals("B-raw: 2\r\n", new String(headers.get(1)));
        assertEquals("C: 3\r\n", new String(headers.get(2)));
    }

    @Test
    void preEncodedHeader_roundTripsThroughGetHeaders() {
        Response r = new Response(200, ContentType.TEXT_PLAIN);
        PreEncodedHeader h = new PreEncodedHeader("X-Static", "value");
        r.header(h);
        assertEquals("X-Static: value\r\n", new String(r.getHeaders().get(0)));
    }
}
