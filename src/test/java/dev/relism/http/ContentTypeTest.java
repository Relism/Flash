package dev.relism.http;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ContentTypeTest {

    // --- getBytes ---

    @Test
    void getBytes_validType() {
        assertArrayEquals("text/plain".getBytes(StandardCharsets.UTF_8), ContentType.TEXT_PLAIN.getBytes());
        assertArrayEquals("application/json".getBytes(StandardCharsets.UTF_8), ContentType.JSON.getBytes());
        assertArrayEquals("image/png".getBytes(StandardCharsets.UTF_8), ContentType.IMAGE_PNG.getBytes());
    }
    
    @Test
    void getBytes_instancesAreNotNull() {
        for (ContentType ct : ContentType.values()) {
            assertNotNull(ct.getBytes());
            assertTrue(ct.getBytes().length > 0);
        }
    }
}
