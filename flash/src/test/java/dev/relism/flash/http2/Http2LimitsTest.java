package dev.relism.flash.http2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Http2LimitsTest {

    @Test
    void maxFrameSizeLocalWithinRfcBounds() {
        // RFC 9113 §6.5.2 — SETTINGS_MAX_FRAME_SIZE must be within 16384..16777215.
        assertTrue(Http2Limits.MAX_FRAME_SIZE_LOCAL >= 16_384);
        assertTrue(Http2Limits.MAX_FRAME_SIZE_LOCAL <= 16_777_215);
    }

    @Test
    void everyLimitIsPositive() {
        assertTrue(Http2Limits.MAX_CONCURRENT_STREAMS > 0);
        assertTrue(Http2Limits.MAX_FRAME_SIZE_LOCAL > 0);
        assertTrue(Http2Limits.MAX_HEADER_LIST_SIZE > 0);
        assertTrue(Http2Limits.MAX_CONTINUATION_FRAMES_PER_BLOCK > 0);
        assertTrue(Http2Limits.MAX_RESET_STREAMS_PER_INTERVAL > 0);
        assertTrue(Http2Limits.RESET_RATE_INTERVAL_MS > 0);
        assertTrue(Http2Limits.MAX_STREAMS_CREATED_PER_INTERVAL > 0);
        assertTrue(Http2Limits.MAX_SETTINGS_ENTRIES_PER_FRAME > 0);
        assertTrue(Http2Limits.MAX_PING_QUEUE_DEPTH > 0);
        assertTrue(Http2Limits.MAX_SETTINGS_PER_INTERVAL > 0);
        assertTrue(Http2Limits.MAX_PINGS_PER_INTERVAL > 0);
        assertTrue(Http2Limits.MAX_USELESS_FRAMES_PER_INTERVAL > 0);
        assertTrue(Http2Limits.MAX_STREAMS_PER_CONNECTION > 0);
        assertTrue(Http2Limits.MAX_EMPTY_DATA_FRAMES_PER_STREAM > 0);
        assertTrue(Http2Limits.INITIAL_WINDOW_SIZE_LOCAL > 0);
        assertTrue(Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL > 0);
        assertTrue(Http2Limits.HPACK_DYNAMIC_TABLE_SIZE_LOCAL > 0);
        assertTrue(Http2Limits.MAX_HPACK_STRING_LENGTH > 0);
        assertTrue(Http2Limits.HEADER_BLOCK_ASSEMBLY_TIMEOUT_MS > 0);
        assertTrue(Http2Limits.STREAM_IDLE_TIMEOUT_MS > 0);
    }

    @Test
    void streamCreationBoundIsAtLeastTheResetBound() {
        // A Rapid Reset defence that only counts resets can be bypassed by a peer that creates
        // streams fast enough that the reset counter never saturates within a window boundary;
        // the creation bound must be at least as tight.
        assertTrue(Http2Limits.MAX_STREAMS_CREATED_PER_INTERVAL >= Http2Limits.MAX_RESET_STREAMS_PER_INTERVAL);
    }

    @Test
    void connectionWindowIsAtLeastAsLargeAsAStreamWindow() {
        // Otherwise a single active stream would be bottlenecked by the connection window
        // before it ever reaches its own (larger) per-stream window.
        assertTrue(Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL >= Http2Limits.INITIAL_WINDOW_SIZE_LOCAL);
    }

    @Test
    void hpackDynamicTableSizeMatchesRfcDefault() {
        // RFC 7541 §4.1 default is 4096; nothing in this codebase should silently diverge.
        assertEquals(4_096, Http2Limits.HPACK_DYNAMIC_TABLE_SIZE_LOCAL);
    }
}
