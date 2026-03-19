package dev.relism;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ChunkedInputStreamTest {

    private static ChunkedInputStream wrap(String chunkedEncoded) {
        byte[] bytes = chunkedEncoded.getBytes(StandardCharsets.UTF_8);
        return new ChunkedInputStream(new ByteArrayInputStream(bytes), null, 0, 0);
    }

    private static String readAll(ChunkedInputStream in) throws IOException {
        return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }

    // --- bulk reads ---

    @Test
    void singleChunk() throws IOException {
        assertEquals("hello", readAll(wrap("5\r\nhello\r\n0\r\n\r\n")));
    }

    @Test
    void multipleChunks() throws IOException {
        assertEquals("hello world", readAll(wrap("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n")));
    }

    @Test
    void emptyBody_terminatorOnly() throws IOException {
        assertEquals("", readAll(wrap("0\r\n\r\n")));
    }

    @Test
    void hexDigitsUppercase() throws IOException {
        // "A" = 10 bytes
        String data = "0123456789";
        assertEquals(data, readAll(wrap("A\r\n" + data + "\r\n0\r\n\r\n")));
    }

    @Test
    void hexDigitsLowercase() throws IOException {
        // "a" = 10 bytes
        String data = "0123456789";
        assertEquals(data, readAll(wrap("a\r\n" + data + "\r\n0\r\n\r\n")));
    }

    @Test
    void chunkExtension_ignored() throws IOException {
        // semicolon and extension are discarded, only size matters
        assertEquals("hello", readAll(wrap("5;ext=val\r\nhello\r\n0\r\n\r\n")));
    }

    @Test
    void trailers_consumed() throws IOException {
        // trailing headers after 0-chunk must be consumed
        assertEquals("hi", readAll(wrap("2\r\nhi\r\n0\r\nTrailer: value\r\n\r\n")));
    }

    // --- byte-by-byte read ---

    @Test
    void byteByByteRead_singleChunk() throws IOException {
        ChunkedInputStream in = wrap("3\r\nabc\r\n0\r\n\r\n");
        assertEquals('a', in.read());
        assertEquals('b', in.read());
        assertEquals('c', in.read());
        assertEquals(-1, in.read());
    }

    @Test
    void byteByByteRead_multipleChunks() throws IOException {
        ChunkedInputStream in = wrap("2\r\nhi\r\n2\r\n!!\r\n0\r\n\r\n");
        assertEquals('h', in.read());
        assertEquals('i', in.read());
        assertEquals('!', in.read());
        assertEquals('!', in.read());
        assertEquals(-1, in.read());
    }

    // --- EOF behaviour ---

    @Test
    void readAfterEof_returnsMinusOne() throws IOException {
        ChunkedInputStream in = wrap("0\r\n\r\n");
        assertEquals(-1, in.read());
        assertEquals(-1, in.read()); // idempotent
    }

    @Test
    void readArrayAfterEof_returnsMinusOne() throws IOException {
        ChunkedInputStream in = wrap("0\r\n\r\n");
        assertEquals(-1, in.read(new byte[8], 0, 8));
    }

    // --- pre-buffered data ---

    @Test
    void preBuf_prependedBeforeSocket() throws IOException {
        // "5\r\nhello" in preBuf, "\r\n0\r\n\r\n" in socket
        byte[] preBuf  = "5\r\nhello".getBytes(StandardCharsets.UTF_8);
        byte[] socket  = "\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        ChunkedInputStream in = new ChunkedInputStream(new ByteArrayInputStream(socket), preBuf, 0, preBuf.length);
        assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void preBuf_withOffset() throws IOException {
        byte[] preBuf = "XX2\r\nhi\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        // offset=2, len=preBuf.length-2 — skip "XX"
        ChunkedInputStream in = new ChunkedInputStream(
                new ByteArrayInputStream(new byte[0]), preBuf, 2, preBuf.length - 2);
        assertEquals("hi", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
}
