package dev.relism.flash;

import dev.relism.flash.http.MalformedRequestException;
import dev.relism.flash.http.Http1Limits;
import dev.relism.flash.transport.BufferedByteSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ChunkedInputStreamTest {

    // BufferedByteSource's Socket reference is only touched when a deadline is set — none of
    // these tests set one, so `null` is safe here.
    private static BufferedByteSource source(byte[] bytes) {
        return new BufferedByteSource(new ByteArrayInputStream(bytes), null);
    }

    private static ChunkedInputStream wrap(String chunkedEncoded) {
        byte[] bytes = chunkedEncoded.getBytes(StandardCharsets.UTF_8);
        return new ChunkedInputStream(source(bytes), null, 0, 0);
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
        assertEquals("hi", readAll(wrap("2\r\nhi\r\n0\r\nX-Trailer: value\r\n\r\n")));
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
        ChunkedInputStream in = new ChunkedInputStream(source(socket), preBuf, 0, preBuf.length);
        assertEquals("hello", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void preBuf_withOffset() throws IOException {
        byte[] preBuf = "XX2\r\nhi\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
        // offset=2, len=preBuf.length-2 — skip "XX"
        ChunkedInputStream in = new ChunkedInputStream(
                source(new byte[0]), preBuf, 2, preBuf.length - 2);
        assertEquals("hi", new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }


    /** Counts every {@code read} call that reaches the wrapped stream — i.e. every syscall. */
    private static final class CountingInputStream extends ByteArrayInputStream {
        int reads = 0;
        CountingInputStream(byte[] buf) { super(buf); }
        @Override public synchronized int read() { reads++; return super.read(); }
        @Override public synchronized int read(byte[] b, int off, int len) { reads++; return super.read(b, off, len); }
    }

    @Test
    void byteByByteRead_doesNotSyscallPerByte() throws IOException {
        // 100 one-byte chunks — the pre-fix implementation would have issued one read() call
        // per payload byte PLUS one per chunk-size digit PLUS two per chunk terminator PLUS
        // two for the final trailer-section terminator: hundreds of underlying reads for 100
        // bytes of payload. Buffered, this must collapse to a small, buffer-size-bound count.
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append("1\r\nx\r\n");
        sb.append("0\r\n\r\n");
        CountingInputStream counting = new CountingInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
        BufferedByteSource src = new BufferedByteSource(counting, null);
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);

        int total = 0;
        while (in.read() != -1) total++;

        assertEquals(100, total);
        // The whole message (700 bytes) fits in BufferedByteSource's default 8 KB buffer, so
        // this must be exactly one underlying read — nowhere near "one per byte".
        assertEquals(1, counting.reads);
    }


    @Test
    void chunkSizeAboveLimit_rejected() {
        // MAX_CHUNK_SIZE is 16 MiB (0x1000000); one hex digit past that overflows the bound.
        BufferedByteSource src = source("10000000\r\n".getBytes(StandardCharsets.UTF_8));
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);
        assertThrows(MalformedRequestException.class, in::read);
    }

    @Test
    void tooManyHexDigits_rejected() {
        BufferedByteSource src = source("00000000000000001\r\n".getBytes(StandardCharsets.UTF_8)); // 17 digits
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);
        assertThrows(MalformedRequestException.class, in::read);
    }

    @Test
    void chunkExtensionTooLong_rejected() {
        String ext = ";" + "a".repeat(300);
        BufferedByteSource src = source(("5" + ext + "\r\nhello\r\n0\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);
        assertThrows(MalformedRequestException.class, in::read);
    }

    @Test
    void malformedChunkSize_rejected() {
        BufferedByteSource src = source(";novalue\r\n".getBytes(StandardCharsets.UTF_8));
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);
        assertThrows(MalformedRequestException.class, in::read);
    }

    @Test
    void bareChunkTerminator_rejected() {
        // Declares 5 bytes but the terminator after them is not CRLF.
        BufferedByteSource src = source("5\r\nhelloXX0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);
        assertThrows(MalformedRequestException.class, () -> in.readAllBytes());
    }

    @Test
    void tooManyChunks_rejected413() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Http1Limits.MAX_CHUNKS_PER_BODY + 5; i++) sb.append("1\r\nx\r\n");
        sb.append("0\r\n\r\n");
        BufferedByteSource src = source(sb.toString().getBytes(StandardCharsets.UTF_8));
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);
        MalformedRequestException e = assertThrows(MalformedRequestException.class, () -> {
            while (in.read() != -1) { /* drain */ }
        });
        assertEquals(413, e.status());
    }

    @Test
    void tooManyTrailers_rejected431() {
        StringBuilder sb = new StringBuilder("2\r\nhi\r\n0\r\n");
        for (int i = 0; i < Http1Limits.MAX_TRAILER_COUNT + 5; i++) sb.append("X-").append(i).append(": v\r\n");
        sb.append("\r\n");
        BufferedByteSource src = source(sb.toString().getBytes(StandardCharsets.UTF_8));
        ChunkedInputStream in = new ChunkedInputStream(src, null, 0, 0);
        MalformedRequestException e = assertThrows(MalformedRequestException.class, in::readAllBytes);
        assertEquals(431, e.status());
    }
}
