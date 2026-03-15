package dev.relism.models;

import dev.relism.http.ContentType;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ResponseTest {

    // --- creation ---

    @Test
    void constructor_withByteArray() {
        byte[] body = "bytes".getBytes(StandardCharsets.UTF_8);
        Response r = new Response(200, body, ContentType.BINARY);
        
        assertEquals(200, r.getStatusCode());
        assertArrayEquals(body, r.getBody());
        assertArrayEquals("application/octet-stream".getBytes(StandardCharsets.UTF_8), r.getContentType());
        assertNotNull(r.toString());
    }

    @Test
    void constructor_withStringText() {
        Response r = new Response(404, "Not Found Text", ContentType.TEXT_PLAIN);
        
        assertEquals(404, r.getStatusCode());
        assertArrayEquals("Not Found Text".getBytes(StandardCharsets.UTF_8), r.getBody());
        assertArrayEquals(ContentType.TEXT_PLAIN.getBytes(), r.getContentType());
    }

    // --- setters ---

    @Test
    void setContentType_byEnum() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setContentType(ContentType.JSON);
        assertArrayEquals(ContentType.JSON.getBytes(), r.getContentType());
    }

    @Test
    void setContentType_byString() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setContentType("application/custom");
        assertArrayEquals("application/custom".getBytes(StandardCharsets.UTF_8), r.getContentType());
    }

    @Test
    void setBody_withByteArray() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        byte[] newBody = "new".getBytes(StandardCharsets.UTF_8);
        r.setBody(newBody);
        assertArrayEquals(newBody, r.getBody());
    }

    @Test
    void setBody_withObjectConvertedToString() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setBody(12345); // auto boxes to Integer, toString called
        assertArrayEquals("12345".getBytes(StandardCharsets.UTF_8), r.getBody());
    }
    
    @Test
    void setStatusCode() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setStatusCode(201);
        assertEquals(201, r.getStatusCode());
    }
}
