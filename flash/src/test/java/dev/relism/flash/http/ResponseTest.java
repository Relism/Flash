package dev.relism.flash.http;

import dev.relism.flash.http.ContentType;
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

    // --- what a field can carry (RFC 9110 5.1, 5.5) ---

    @Test
    void headerAcceptsVisibleAsciiSpacesAndTabs() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.header("X-Ok", "a b\tc ~!\"(),/:;<=>?@[\\]{}").header("X-Count", 3).type("text/plain; charset=utf-8");
        new PreEncodedHeader("Cache-Control", "no-store");
    }

    /** Every way text becomes a field refuses what would end the field early or reach the wire as another byte. */
    @Test
    void everyWayIntoAFieldRefusesWhatAFieldCannotCarry() {
        for (String value : new String[]{"a\r\nSet-Cookie: x=1", "a\nb", "a\u0000b", "a\u007Fb", "caf\u00E9", "\u010D\u010A"}) {
            Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
            assertThrows(IllegalArgumentException.class, () -> r.header("X-Value", value), value);
            assertThrows(IllegalArgumentException.class, () -> r.type(value), value);
            assertThrows(IllegalArgumentException.class, () -> r.trailer("X-Value", value), value);
            assertThrows(IllegalArgumentException.class, () -> new PreEncodedHeader("X-Value", value), value);
            assertThrows(IllegalArgumentException.class, () -> HttpException.badRequest("no").header("X-Value", value), value);
        }
        for (String name : new String[]{"", "X Value", "X-Value:", "X\r\nY", ":path", "X\u00E9"}) {
            Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
            assertThrows(IllegalArgumentException.class, () -> r.header(name, "v"), name);
            assertThrows(IllegalArgumentException.class, () -> r.header(name, 1), name);
            assertThrows(IllegalArgumentException.class, () -> new PreEncodedHeader(name, "v"), name);
        }
    }

    /** A content type kept as bytes is checked too: an array may come from a request as easily as a string. */
    @Test
    void aContentTypeGivenAsBytesIsCheckedLikeAString() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.type("text/plain".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class, () -> r.type("text/plain\r\nSet-Cookie: x=1".getBytes(StandardCharsets.US_ASCII)));
        assertThrows(IllegalArgumentException.class, () -> r.type("caf\u00E9".getBytes(StandardCharsets.UTF_8)));
    }

    /** The message names the header and where, never the value: a header may carry a secret. */
    @Test
    void theRefusalDoesNotRepeatTheValue() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        String message = assertThrows(IllegalArgumentException.class, () -> r.header("Authorization", "Bearer s3cret\n")).getMessage();
        assertTrue(message.contains("Authorization") && !message.contains("s3cret"), message);
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
    void setBody_withString() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setBody("hello");
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), r.getBody());
    }

    @Test
    void setBody_withCharSequence() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.setBody(new StringBuilder("hi"));
        assertArrayEquals("hi".getBytes(StandardCharsets.UTF_8), r.getBody());
    }

    @Test
    void setBody_withUnsupportedType_throws() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        assertThrows(IllegalArgumentException.class, () -> r.setBody(12345));
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


    @Test
    void header_exceedingMaxCount_throws() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        for (int i = 0; i < dev.relism.flash.http.Http1Limits.MAX_RESPONSE_HEADER_COUNT; i++) {
            r.header("X-" + i, "v");
        }
        assertThrows(IllegalStateException.class, () -> r.header("one-too-many", "v"));
    }

    @Test
    void header_exceedingMaxRegionBytes_throws() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        String bigValue = "v".repeat(1024);
        assertThrows(IllegalStateException.class, () -> {
            // Each call adds ~1024 bytes; comfortably crosses MAX_RESPONSE_HEADER_BYTES well
            // before MAX_RESPONSE_HEADER_COUNT would trigger first.
            for (int i = 0; i < dev.relism.flash.http.Http1Limits.MAX_RESPONSE_HEADER_COUNT; i++) {
                r.header("X-" + i, bigValue);
            }
        });
    }

    @Test
    void header_withinBudget_stillWorksNormally() {
        Response r = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
        r.header("X-Foo", "bar");
        assertEquals(1, r.getHeaders().size());
    }
}
