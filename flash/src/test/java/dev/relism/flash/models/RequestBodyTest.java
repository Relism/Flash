package dev.relism.flash.models;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RequestBodyTest {

    // --- static factories ---

    @Test
    void of_resolvedImmediately() {
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        RequestBody body = RequestBody.of(data);
        assertArrayEquals(data, body.bytes());
        assertFalse(body.isEmpty());
        assertEquals(5, body.contentLength());
    }

    @Test
    void empty_isEmptyAndZeroLength() {
        RequestBody body = RequestBody.empty();
        assertTrue(body.isEmpty());
        assertEquals(0, body.contentLength());
        assertEquals(0, body.bytes().length);
    }

    // --- bytes() ---

    @Test
    void bytes_fromPreBufOnly() {
        byte[] preBuf = "world".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(null, 5, preBuf, 0, 5);
        assertArrayEquals(preBuf, body.bytes());
    }

    @Test
    void bytes_fromPreBufWithOffset() {
        byte[] preBuf = "xxhelloxx".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(null, 5, preBuf, 2, 5);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), body.bytes());
    }

    @Test
    void bytes_fromSocketOnly() {
        byte[] data = "socket".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(new ByteArrayInputStream(data), 6, new byte[0], 0, 0);
        assertArrayEquals(data, body.bytes());
    }

    @Test
    void bytes_fromPreBufAndSocket() {
        byte[] preBuf = "hel".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(
                new ByteArrayInputStream("lo".getBytes(StandardCharsets.UTF_8)),
                5, preBuf, 0, 3);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), body.bytes());
    }

    @Test
    void bytes_cached_returnsSameReference() {
        RequestBody body = RequestBody.of("cached".getBytes(StandardCharsets.UTF_8));
        assertSame(body.bytes(), body.bytes());
    }

    @Test
    void bytes_chunked_readsAllFromSocket() {
        // contentLength == -1 → bytes() calls socket.readAllBytes()
        byte[] data = "chunked content".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(new ByteArrayInputStream(data), -1L, null, 0, 0);
        assertArrayEquals(data, body.bytes());
    }

    @Test
    void bytes_tooLarge_throwsIllegalStateException() {
        RequestBody body = new RequestBody(InputStream.nullInputStream(), (long) Integer.MAX_VALUE + 1, null, 0, 0);
        assertThrows(IllegalStateException.class, body::bytes);
    }

    // --- stream() ---

    @Test
    void stream_onResolved_returnsBytesWrapped() throws IOException {
        byte[] data = "stream".getBytes(StandardCharsets.UTF_8);
        RequestBody body = RequestBody.of(data);
        assertArrayEquals(data, body.stream().readAllBytes());
    }

    @Test
    void stream_fromPreBufOnly() throws IOException {
        byte[] preBuf = "buf".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(null, 3, preBuf, 0, 3);
        assertArrayEquals(preBuf, body.stream().readAllBytes());
    }

    @Test
    void stream_fromPreBufAndSocket() throws IOException {
        byte[] preBuf = "hel".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(
                new ByteArrayInputStream("lo".getBytes(StandardCharsets.UTF_8)),
                5, preBuf, 0, 3);
        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), body.stream().readAllBytes());
    }

    @Test
    void stream_chunked_returnsSocketDirectly() {
        InputStream socket = InputStream.nullInputStream();
        RequestBody body = new RequestBody(socket, -1L, null, 0, 0);
        assertSame(socket, body.stream());
    }

    @Test
    void stream_afterBytes_returnsCachedBytes() throws IOException {
        byte[] data = "data".getBytes(StandardCharsets.UTF_8);
        RequestBody body = new RequestBody(new ByteArrayInputStream(data), 4, new byte[0], 0, 0);
        body.bytes(); // resolves and caches
        assertArrayEquals(data, body.stream().readAllBytes()); // wraps cached bytes
    }

    // --- drain() ---

    @Test
    void drain_empty_noOp() {
        assertDoesNotThrow(RequestBody.empty()::drain);
    }

    @Test
    void drain_resolved_noOp() {
        assertDoesNotThrow(RequestBody.of("data".getBytes())::drain);
    }

    @Test
    void drain_skipsUnreadSocketBytes() throws IOException {
        byte[] payload = "helloNEXT".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream socket = new ByteArrayInputStream(payload);
        RequestBody body = new RequestBody(socket, 5, new byte[0], 0, 0);
        body.drain();
        assertArrayEquals("NEXT".getBytes(StandardCharsets.UTF_8), socket.readAllBytes());
    }

    @Test
    void drain_skipsOnlyRemainingAfterPartialPreBuf() throws IOException {
        byte[] preBuf = "hel".getBytes(StandardCharsets.UTF_8);
        byte[] rest   = "loNEXT".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream socket = new ByteArrayInputStream(rest);
        // body = "hello" (5 bytes), 3 in preBuf, 2 from socket
        RequestBody body = new RequestBody(socket, 5, preBuf, 0, 3);
        body.drain();
        // drain should skip 2 socket bytes ("lo"), leaving "NEXT"
        assertArrayEquals("NEXT".getBytes(StandardCharsets.UTF_8), socket.readAllBytes());
    }

    @Test
    void drain_chunked_drainsSocket() throws IOException {
        byte[] data = "some chunked data".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream socket = new ByteArrayInputStream(data);
        RequestBody body = new RequestBody(socket, -1L, null, 0, 0);
        body.drain();
        assertEquals(0, socket.available());
    }
}
