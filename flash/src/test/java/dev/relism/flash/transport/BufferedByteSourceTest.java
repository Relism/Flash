package dev.relism.flash.transport;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * end-to-end tests, which never hit the {@code null}-socket path every isolated unit test in
 * this codebase actually uses. Found and fixed while building {@code Http2FrameReaderTest}
 */
class BufferedByteSourceTest {

    private static BufferedByteSource sourceOf(String s) {
        return new BufferedByteSource(new ByteArrayInputStream(s.getBytes(StandardCharsets.US_ASCII)), null);
    }

    // ── Plain InputStream passthrough ───────────────────────────────────────

    @Test
    void read_singleByte() throws IOException {
        BufferedByteSource src = sourceOf("AB");
        assertEquals('A', src.read());
        assertEquals('B', src.read());
        assertEquals(-1, src.read());
    }

    @Test
    void read_intoArray() throws IOException {
        BufferedByteSource src = sourceOf("hello world");
        byte[] buf = new byte[5];
        int n = src.read(buf, 0, 5);
        assertEquals(5, n);
        assertEquals("hello", new String(buf, StandardCharsets.US_ASCII));
    }

    @Test
    void read_largerThanInternalBuffer_bypassesBufferCorrectly() throws IOException {
        String big = "x".repeat(20_000);
        BufferedByteSource src = new BufferedByteSource(
                new ByteArrayInputStream(big.getBytes(StandardCharsets.US_ASCII)), null, 4096);
        byte[] out = new byte[20_000];
        int total = 0;
        while (total < out.length) {
            int n = src.read(out, total, out.length - total);
            if (n < 0) break;
            total += n;
        }
        assertEquals(20_000, total);
    }

    // ── peek / prependOnce ───────────────────────────────────────────────────

    @Test
    void peek_doesNotConsume() throws IOException {
        BufferedByteSource src = sourceOf("abcdef");
        byte[] dst = new byte[3];
        int n = src.peek(dst, 0, 3);
        assertEquals(3, n);
        assertEquals("abc", new String(dst, StandardCharsets.US_ASCII));
        // Still readable from the start — peek must not have advanced the position.
        assertEquals('a', src.read());
        assertEquals('b', src.read());
    }

    @Test
    void peek_rejectsLengthAboveBufferCapacity() {
        BufferedByteSource src = new BufferedByteSource(new ByteArrayInputStream(new byte[0]), null, 16);
        assertThrows(IllegalArgumentException.class, () -> src.peek(new byte[20], 0, 20));
    }

    @Test
    void prependOnce_servedBeforeUnderlyingBytes() throws IOException {
        BufferedByteSource src = sourceOf("world");
        byte[] prefix = "hello ".getBytes(StandardCharsets.US_ASCII);
        src.prependOnce(prefix, 0, prefix.length);

        byte[] out = new byte[11];
        int total = 0;
        while (total < out.length) {
            int n = src.read(out, total, out.length - total);
            if (n < 0) break;
            total += n;
        }
        assertEquals("hello world", new String(out, 0, total, StandardCharsets.US_ASCII));
    }

    @Test
    void prependOnce_rejectsSecondCallBeforeFirstIsConsumed() {
        BufferedByteSource src = sourceOf("x");
        byte[] a = "a".getBytes(StandardCharsets.US_ASCII);
        src.prependOnce(a, 0, 1);
        assertThrows(IllegalStateException.class, () -> src.prependOnce(a, 0, 1));
    }


    @Test
    void clearDeadline_withNullSocket_doesNotThrow() throws IOException {
        BufferedByteSource src = sourceOf("data");
        src.setDeadline(System.nanoTime() + 1_000_000_000L);
        assertDoesNotThrow(src::clearDeadline);
    }

    @Test
    void deadlineAlreadyExpired_throwsSocketTimeoutException_evenWithNullSocket() {
        BufferedByteSource src = sourceOf(""); // empty: forces fillFromUnderlying on the next read
        src.setDeadline(System.nanoTime() - 1_000_000_000L); // already in the past
        assertThrows(SocketTimeoutException.class, () -> src.read(new byte[1], 0, 1));
    }

    @Test
    void deadlineNotYetExpired_readsNormally_withNullSocket() throws IOException {
        BufferedByteSource src = sourceOf("z");
        src.setDeadline(System.nanoTime() + 30_000_000_000L); // 30s in the future
        assertEquals('z', src.read());
    }

    @Test
    void bytesAlreadyBuffered_areServedRegardlessOfDeadline() throws IOException {
        // peek() fills the internal buffer without a deadline; a since-expired deadline must not
        // block already-buffered bytes from being read (only underlying-stream reads are bounded).
        BufferedByteSource src = sourceOf("buffered");
        src.peek(new byte[8], 0, 8);
        src.setDeadline(System.nanoTime() - 1); // already expired
        assertEquals('b', src.read()); // served from the buffer — no underlying read needed
    }

    @Test
    void clearDeadline_thenRead_neverThrowsTimeoutAfterward() throws IOException {
        BufferedByteSource src = sourceOf("ok");
        src.setDeadline(System.nanoTime() - 1); // expired
        src.clearDeadline();
        assertEquals('o', src.read()); // deadline cleared — must not time out
    }

    // ── available / skip / close ─────────────────────────────────────────────

    @Test
    void skip_advancesPastBufferedAndUnderlyingBytes() throws IOException {
        BufferedByteSource src = sourceOf("abcdef");
        long skipped = src.skip(3);
        assertEquals(3, skipped);
        assertEquals('d', src.read());
    }

    @Test
    void close_delegatesToUnderlyingStream() {
        java.io.InputStream[] closed = new java.io.InputStream[1];
        java.io.InputStream in = new ByteArrayInputStream(new byte[0]) {
            @Override public void close() throws IOException { closed[0] = this; super.close(); }
        };
        BufferedByteSource src = new BufferedByteSource(in, null);
        assertDoesNotThrow(src::close);
        assertSame(in, closed[0]);
    }
}
