package dev.relism.flash.websocket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** RFC 6455 framing over an in-memory duplex: reassembly, validation, close codes and what goes out. */
class WebSocketFramingTest {

    private static final int TEXT = 1, BINARY = 2, CONTINUATION = 0, CLOSE = 8, PING = 9;
    private static final byte[] MASK = {1, 2, 3, 4};

    /** Bytes in, at most {@code window} at a time, and everything written out. */
    static final class MemoryDuplex extends Duplex {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final byte[] in;
        final int window;
        int position;

        MemoryDuplex(byte[] in, int window) {
            this.in = in;
            this.window = window;
        }

        @Override public void write(byte[] b, int off, int len) { written.write(b, off, len); }
        @Override public void flush() {}
        @Override public int fill() {
            int left = in.length - position;
            if (left == 0) return -1;
            int end = (position / window + 1) * window; // a window ends where the next one starts
            return Math.min(left, end - position);
        }
        @Override public byte[] array() { return in; }
        @Override public int offset() { return position; }
        @Override public void advance(int n) { position += n; }
    }

    private static byte[] frame(int opcode, boolean fin, boolean masked, byte[] payload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write((fin ? 0x80 : 0) | opcode);
        int maskBit = masked ? 0x80 : 0;
        if (payload.length <= 125) {
            buf.write(maskBit | payload.length);
        } else {
            buf.write(maskBit | 126);
            buf.write(payload.length >> 8);
            buf.write(payload.length);
        }
        byte[] out = payload.clone();
        if (masked) {
            buf.write(MASK);
            for (int i = 0; i < out.length; i++) out[i] ^= MASK[i % 4];
        }
        buf.write(out);
        return buf.toByteArray();
    }

    private static byte[] text(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) out.writeBytes(part);
        return out.toByteArray();
    }

    private static MemoryDuplex duplex(byte[]... frames) {
        return new MemoryDuplex(concat(frames), 1 << 20);
    }

    private static WebSocketFraming.Session channel(MemoryDuplex duplex, int maxMessage) {
        return new WebSocketFraming.Session(duplex, maxMessage);
    }

    private static String message(WebSocketFraming.Session channel) {
        return new String(channel.array(), channel.offset(), channel.length(), StandardCharsets.UTF_8);
    }

    private static int refused(int maxMessage, byte[]... frames) {
        WebSocketFraming.Session channel = channel(duplex(frames), maxMessage);
        return assertThrows(WebSocketProtocolException.class, channel::next).closeCode();
    }

    @Test
    void aWholeFrameInTheReadBufferIsHandedOutWhereItLies() throws IOException {
        MemoryDuplex duplex = duplex(frame(TEXT, true, true, text("hello")));
        WebSocketFraming.Session channel = channel(duplex, 64);

        assertTrue(channel.next());
        assertSame(duplex.in, channel.array());
        assertEquals("hello", message(channel));
        assertFalse(channel.next());
    }

    @Test
    void fragmentsAndBufferBoundariesAreGatheredAndAPingInBetweenIsAnswered() throws IOException {
        byte[] wire = concat(
                frame(TEXT, false, true, text("hel")),
                frame(PING, true, true, text("ping")),
                frame(CONTINUATION, false, true, text("lo ")),
                frame(CONTINUATION, true, true, text("world")),
                frame(BINARY, true, true, new byte[300]));
        MemoryDuplex duplex = new MemoryDuplex(wire, 7); // every frame straddles a window
        WebSocketFraming.Session channel = channel(duplex, 512);

        assertTrue(channel.next());
        assertEquals("hello world", message(channel));
        assertTrue(channel.next());
        assertEquals(300, channel.length());
        assertArrayEquals(concat(new byte[] {(byte) 0x8A, 4}, text("ping")), duplex.written.toByteArray());
    }

    @Test
    void emptyFragmentsMakeAnEmptyMessage() throws IOException {
        WebSocketFraming.Session channel = channel(duplex(
                frame(TEXT, false, true, new byte[0]),
                frame(CONTINUATION, false, true, new byte[0]),
                frame(CONTINUATION, true, true, new byte[0])), 64);
        assertTrue(channel.next());
        assertEquals(0, channel.length());
    }

    @Test
    void brokenFramesCloseWithTheCodeRfc6455Names() throws IOException {
        byte[] rsv = frame(TEXT, true, true, text("hi"));
        rsv[0] |= 0x40;
        assertEquals(1002, refused(64, rsv));
        assertEquals(1002, refused(64, frame(TEXT, true, false, text("hi"))));         // unmasked
        assertEquals(1002, refused(64, frame(3, true, true, new byte[0])));            // reserved opcode
        assertEquals(1002, refused(64, frame(CONTINUATION, true, true, text("x"))));   // nothing to continue
        assertEquals(1002, refused(64, frame(TEXT, false, true, text("a")), frame(TEXT, true, true, text("b"))));
        assertEquals(1002, refused(64, frame(PING, false, true, text("x"))));          // fragmented control
        assertEquals(1002, refused(200, frame(PING, true, true, new byte[126])));      // oversized control
        assertEquals(1009, refused(8, frame(TEXT, false, true, new byte[5]), frame(CONTINUATION, true, true, new byte[5])));
        assertEquals(1007, refused(64, frame(TEXT, true, true, new byte[] {(byte) 0xF4, (byte) 0x90, (byte) 0x80, (byte) 0x80})));
        assertEquals(1007, refused(64, frame(TEXT, false, true, new byte[] {(byte) 0xCE}), frame(CONTINUATION, true, true, new byte[0])));
        assertEquals(1002, refused(64, frame(CLOSE, true, true, new byte[] {0x03, (byte) 0xED}))); // 1005 never travels
    }

    @Test
    void aPeerCloseIsAnsweredWithItsOwnCodeAndEndsTheMessages() throws IOException {
        MemoryDuplex duplex = duplex(frame(CLOSE, true, true,
                new byte[] {0x03, (byte) 0xE9, 'b', 'y', 'e'})); // 1001, "bye"
        WebSocketFraming.Session channel = channel(duplex, 64);

        assertFalse(channel.next());
        assertEquals(1001, channel.closeCode());
        assertEquals("bye", channel.closeReason());
        assertArrayEquals(new byte[] {(byte) 0x88, 5, 0x03, (byte) 0xE9, 'b', 'y', 'e'}, duplex.written.toByteArray());
        channel.close(null); // the handler returns: nothing more goes out
        assertEquals(7, duplex.written.size());
    }

    @Test
    void anApplicationCloseCarriesItsReason() throws Exception {
        MemoryDuplex duplex = duplex();
        WebSocketFraming.Session channel = channel(duplex, 64);

        channel.close(1000, "done");
        channel.await();

        assertArrayEquals(new byte[] {(byte) 0x88, 6, 0x03, (byte) 0xE8, 'd', 'o', 'n', 'e'},
                duplex.written.toByteArray());
        assertEquals("done", channel.closeReason());
    }

    @Test
    void sendPicksTextOrBinaryByTheEncoderAndCloseNamesTheFailure() throws IOException {
        MemoryDuplex duplex = duplex();
        WebSocketFraming.Session channel = channel(duplex, 64);

        channel.send(WebSocketFraming.Session.TEXT, text("hi"), 0, 2);
        channel.send(WebSocketFraming.Session.BINARY, new byte[200], 0, 200);
        channel.close(HttpException.badRequest("not a message this codec reads"));

        byte[] out = duplex.written.toByteArray();
        assertArrayEquals(new byte[] {(byte) 0x81, 2, 'h', 'i'}, Arrays.copyOfRange(out, 0, 4));
        assertArrayEquals(new byte[] {(byte) 0x82, 126, 0, (byte) 200}, Arrays.copyOfRange(out, 4, 8));
        assertArrayEquals(new byte[] {(byte) 0x88, 2, 0x03, (byte) 0xEF}, Arrays.copyOfRange(out, 208, 212)); // 1007
        assertThrows(IOException.class,
                () -> channel.send(WebSocketFraming.Session.TEXT, text("late"), 0, 4));
    }
}
