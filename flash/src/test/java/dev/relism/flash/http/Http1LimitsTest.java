package dev.relism.flash.http;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Http1LimitsTest {

    @Test
    void everyLimitIsPositive() {
        assertTrue(Http1Limits.MAX_HEADER_COUNT > 0);
        assertTrue(Http1Limits.MAX_HEADER_NAME_LENGTH > 0);
        assertTrue(Http1Limits.MAX_HEADER_VALUE_LENGTH > 0);
        assertTrue(Http1Limits.MAX_REQUEST_LINE_LENGTH > 0);
    }
}
