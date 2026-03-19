package dev.relism.models;

import dev.relism.http.ContentType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ResponseTest {

    // --- construction ---

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

    // --- fluent mutators ---

    @Test
    void type_byEnum() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.type(ContentType.JSON);
        assertArrayEquals(ContentType.JSON.getBytes(), r.getContentType());
    }

    @Test
    void type_byString() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.type("application/custom");
        assertArrayEquals("application/custom".getBytes(StandardCharsets.UTF_8), r.getContentType());
    }

    @Test
    void body_withByteArray() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        byte[] newBody = "new".getBytes(StandardCharsets.UTF_8);
        r.body(newBody);
        assertArrayEquals(newBody, r.getBody());
    }

    @Test
    void setBody_withObjectConvertedToString() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setBody(12345);
        assertArrayEquals("12345".getBytes(StandardCharsets.UTF_8), r.getBody());
    }

    @Test
    void setStatusCode() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setStatusCode(201);
        assertEquals(201, r.getStatusCode());
    }

    // --- streaming ---

    @Test
    void stream_knownLength() {
        InputStream src = new ByteArrayInputStream("body".getBytes());
        Response r = new Response(200, ContentType.TEXT_PLAIN).stream(src, 4L);

        assertTrue(r.isStreaming());
        assertFalse(r.isChunked());
        assertEquals(4L, r.getStreamLength());
        assertSame(src, r.getStream());
        assertNull(r.getBody());
    }

    @Test
    void chunked_setsChunkedFlag() {
        InputStream src = new ByteArrayInputStream("x".getBytes());
        Response r = new Response(200, ContentType.TEXT_PLAIN).chunked(src);

        assertTrue(r.isStreaming());
        assertTrue(r.isChunked());
        assertSame(src, r.getStream());
        assertNull(r.getBody());
    }

    @Test
    void isStreaming_falseForFixedBody() {
        assertFalse(new Response(200, new byte[0], ContentType.TEXT_PLAIN).isStreaming());
    }

    // --- header ---

    @Test
    void header_encodedCorrectly() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.header("X-Foo", "bar");
        assertArrayEquals("X-Foo: bar\r\n".getBytes(StandardCharsets.UTF_8), r.getHeaders().get(0));
    }

    @Test
    void header_chaining_returnsSelf() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        assertSame(r, r.header("A", "1").header("B", "2"));
        assertEquals(2, r.getHeaders().size());
    }

    @Test
    void getHeaders_emptyWhenNoneAdded() {
        assertTrue(new Response(200, new byte[0], ContentType.TEXT_PLAIN).getHeaders().isEmpty());
    }
}
