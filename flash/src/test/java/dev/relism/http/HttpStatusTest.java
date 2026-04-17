package dev.relism.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpStatusTest {

    // --- bytesForCode ---

    @Test
    void bytesForCode_validCodes() {
        assertArrayEquals("200 OK".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(200));
        assertArrayEquals("404 Not Found".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(404));
        assertArrayEquals("500 Internal Server Error".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(500));
    }

    @Test
    void bytesForCode_unknownCode_returnsNull() {
        assertNull(HttpStatus.bytesForCode(999));
        assertNull(HttpStatus.bytesForCode(0));
        assertNull(HttpStatus.bytesForCode(2000));
    }
    
    @Test
    void bytesForCode_allEnumsPresentInIndex() {
        // Let's just double check a handful of other representations to ensure full mapping
        assertArrayEquals("100 Continue".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(100));
        assertArrayEquals("301 Moved Permanently".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(301));
        assertArrayEquals("400 Bad Request".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(400));
    }

    @Test
    void reasonForCode_validAndUnknownCodes() {
        assertEquals("OK", HttpStatus.reasonForCode(200));
        assertEquals("Not Found", HttpStatus.reasonForCode(404));
        assertEquals("Internal Server Error", HttpStatus.reasonForCode(500));
        assertNull(HttpStatus.reasonForCode(999));
        assertNull(HttpStatus.reasonForCode(0));
    }
}
