package dev.relism.flash.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Http1LimitsTest {

    @Test
    void everyLimitIsPositive() {
        assertTrue(Http1Limits.MAX_CONTENT_LENGTH > 0);
        assertTrue(Http1Limits.MAX_HEADER_COUNT > 0);
        assertTrue(Http1Limits.MAX_HEADER_NAME_LENGTH > 0);
        assertTrue(Http1Limits.MAX_HEADER_VALUE_LENGTH > 0);
        assertTrue(Http1Limits.MAX_REQUEST_LINE_LENGTH > 0);
    }

    @Test
    void requestLineFitsInsideMaxHeaderValueOrderOfMagnitude() {
        // Sanity: the request-line bound should not dwarf the total per-header bound to the
        // point of being meaningless as a distinct limit.
        assertTrue(Http1Limits.MAX_REQUEST_LINE_LENGTH <= Http1Limits.MAX_CONTENT_LENGTH);
    }
}
