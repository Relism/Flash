package dev.relism.flash.http;

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


    @Test
    void statusesAboveThePreviousHandMaintainedBound_workCorrectly() {
        // The bound used to be hardcoded at 504; any of these (all >504, all needed by h1
        // hardening or h2) used to throw ArrayIndexOutOfBoundsException from the static
        // initializer at class-load time.
        assertArrayEquals("421 Misdirected Request".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(421));
        assertArrayEquals("431 Request Header Fields Too Large".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(431));
        assertArrayEquals("505 HTTP Version Not Supported".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(505));
        assertArrayEquals("507 Insufficient Storage".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(507));
        assertArrayEquals("511 Network Authentication Required".getBytes(StandardCharsets.UTF_8), HttpStatus.bytesForCode(511));
    }

    @Test
    void everyEnumConstant_hasAWorkingBytesForCodeEntry() {
        for (HttpStatus s : HttpStatus.values()) {
            assertNotNull(HttpStatus.bytesForCode(s.code()), s.name());
            assertNotNull(HttpStatus.reasonForCode(s.code()), s.name());
        }
    }
}
