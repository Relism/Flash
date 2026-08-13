package dev.relism.flash.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * masking, opcode validation, control-frame constraints, correct close codes) coverage for
 * {@link WebSocketSession#readFrame}.
 */
class WebSocketFragmentationAndValidationTest {

    private static final byte[] MASK = {1, 2, 3, 4};

    private static byte[] frame(int opcode, boolean fin, boolean masked, byte[] payload) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        buf.write((fin ? 0x80 : 0) | opcode);
        int len = payload.length;
        int maskBit = masked ? 0x80 : 0x00;
        if (len <= 125) {
            buf.write(maskBit | len);
        } else {
            buf.write(maskBit | 126);
            buf.write((len >> 8) & 0xFF);
            buf.write(len & 0xFF);
        }
        byte[] out = payload;
        if (masked) {
            buf.write(MASK);
            out = payload.clone();
            for (int i = 0; i < out.length; i++) out[i] ^= MASK[i % 4];
        }
        buf.write(out);
        return buf.toByteArray();
    }

    private static byte[] concat(byte[]... arrays) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] a : arrays) out.write(a);
        return out.toByteArray();
    }

    /** Server-mode session (masked incoming required) over the given raw bytes. */
    private static WebSocketSession serverSession(byte[] raw, int bufferSize) {
        return new WebSocketSession(new ByteArrayInputStream(raw), new ByteArrayOutputStream(), bufferSize);
    }


    @Test
    void continuationFrames_reassembleIntoOneMessage() throws IOException {
        byte[] raw = concat(
                frame(WebSocketFrame.OP_TEXT, false, true, "hel".getBytes(StandardCharsets.UTF_8)),
                frame(WebSocketFrame.OP_CONTINUATION, false, true, "lo ".getBytes(StandardCharsets.UTF_8)),
                frame(WebSocketFrame.OP_CONTINUATION, true, true, "world".getBytes(StandardCharsets.UTF_8)));
        WebSocketSession session = serverSession(raw, 64);
        WebSocketFrame frame = new WebSocketFrame();

        assertTrue(session.readFrame(frame));
        assertEquals(WebSocketFrame.OP_TEXT, frame.opcode());
        assertTrue(frame.isFin());
        assertEquals("hello world", new String(frame.copyPayload(), StandardCharsets.UTF_8));
    }

    @Test
    void controlFrame_interleavedDuringFragmentation_deliveredWithoutDisturbingReassembly() throws IOException {
        byte[] raw = concat(
                frame(WebSocketFrame.OP_TEXT, false, true, "AB".getBytes(StandardCharsets.UTF_8)),
                frame(WebSocketFrame.OP_PING, true, true, "ping".getBytes(StandardCharsets.UTF_8)),
                frame(WebSocketFrame.OP_CONTINUATION, true, true, "CD".getBytes(StandardCharsets.UTF_8)));
        WebSocketSession session = serverSession(raw, 64);
        WebSocketFrame frame = new WebSocketFrame();

        assertTrue(session.readFrame(frame));
        assertEquals(WebSocketFrame.OP_PING, frame.opcode());
        assertEquals("ping", new String(frame.copyPayload(), StandardCharsets.UTF_8));

        assertTrue(session.readFrame(frame));
        assertEquals(WebSocketFrame.OP_TEXT, frame.opcode());
        assertEquals("ABCD", new String(frame.copyPayload(), StandardCharsets.UTF_8));
    }

    @Test
    void continuationWithoutInitiatedMessage_rejected1002() throws IOException {
        byte[] raw = frame(WebSocketFrame.OP_CONTINUATION, true, true, "x".getBytes(StandardCharsets.UTF_8));
        WebSocketSession session = serverSession(raw, 64);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1002, e.closeCode());
    }

    @Test
    void newDataFrameWhileFragmenting_rejected1002() throws IOException {
        byte[] raw = concat(
                frame(WebSocketFrame.OP_TEXT, false, true, "a".getBytes(StandardCharsets.UTF_8)),
                frame(WebSocketFrame.OP_TEXT, true, true, "b".getBytes(StandardCharsets.UTF_8)));
        WebSocketSession session = serverSession(raw, 64);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1002, e.closeCode());
    }

    @Test
    void reassembledMessageExceedingBuffer_rejected1009() throws IOException {
        byte[] raw = concat(
                frame(WebSocketFrame.OP_TEXT, false, true, new byte[5]),
                frame(WebSocketFrame.OP_CONTINUATION, true, true, new byte[5]));
        WebSocketSession session = serverSession(raw, 8); // 5 + 5 = 10 > 8
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1009, e.closeCode());
    }


    @Test
    void serverSession_unmaskedIncomingFrame_rejected1002() throws IOException {
        byte[] raw = frame(WebSocketFrame.OP_TEXT, true, false, "hi".getBytes(StandardCharsets.UTF_8));
        WebSocketSession session = serverSession(raw, 64);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1002, e.closeCode());
    }

    @Test
    void clientSession_maskedIncomingFrame_rejected1002() throws IOException {
        byte[] raw = frame(WebSocketFrame.OP_TEXT, true, true, "hi".getBytes(StandardCharsets.UTF_8));
        WebSocketSession session = new WebSocketSession(
                new ByteArrayInputStream(raw), new ByteArrayOutputStream(), 64, null, true);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1002, e.closeCode());
    }

    @Test
    void clientSession_unmaskedIncomingFrame_accepted() throws IOException {
        byte[] raw = frame(WebSocketFrame.OP_TEXT, true, false, "hi".getBytes(StandardCharsets.UTF_8));
        WebSocketSession session = new WebSocketSession(
                new ByteArrayInputStream(raw), new ByteArrayOutputStream(), 64, null, true);
        WebSocketFrame frame = new WebSocketFrame();
        assertTrue(session.readFrame(frame));
        assertEquals("hi", new String(frame.copyPayload(), StandardCharsets.UTF_8));
    }


    @Test
    void reservedOpcode_rejected1002() throws IOException {
        byte[] raw = frame(0x3, true, true, new byte[0]); // 0x3 is reserved
        WebSocketSession session = serverSession(raw, 64);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1002, e.closeCode());
    }


    @Test
    void fragmentedControlFrame_rejected1002() throws IOException {
        byte[] raw = frame(WebSocketFrame.OP_PING, false, true, "x".getBytes(StandardCharsets.UTF_8));
        WebSocketSession session = serverSession(raw, 64);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1002, e.closeCode());
    }

    @Test
    void oversizedControlFramePayload_rejected1002() throws IOException {
        byte[] raw = frame(WebSocketFrame.OP_PING, true, true, new byte[126]);
        WebSocketSession session = serverSession(raw, 200);
        WebSocketProtocolException e = assertThrows(WebSocketProtocolException.class,
                () -> session.readFrame(new WebSocketFrame()));
        assertEquals(1002, e.closeCode());
    }

    @Test
    void controlFrameAtTheMaxAllowedSize_accepted() throws IOException {
        byte[] raw = frame(WebSocketFrame.OP_PING, true, true, new byte[125]);
        WebSocketSession session = serverSession(raw, 200);
        WebSocketFrame frame = new WebSocketFrame();
        assertTrue(session.readFrame(frame));
        assertEquals(125, frame.payloadLength());
    }


    private static final class CountingInputStream extends InputStream {
        private final InputStream delegate;
        int reads = 0;
        CountingInputStream(InputStream delegate) { this.delegate = delegate; }
        @Override public int read() throws IOException { reads++; return delegate.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { reads++; return delegate.read(b, off, len); }
    }

    @Test
    void readFrame_withExtendedLengthAndMask_doesNotReadOneByteAtATime() throws IOException {
        // 200-byte payload forces the 16-bit extended length; masked, so 4 mask bytes too.
        // Pre-fix: 1 (b0) + 1 (b1) + 2 (extended length, read one at a time originally via two
        // separate in.read() calls — already bulk-free in that part) + 4 (mask, one at a time)
        // = several individual reads for the header alone, on top of one per payload byte if
        // the underlying stream were unbuffered. Post-fix: the header's variable remainder
        // (length + mask) is exactly one readFully call.
        byte[] raw = frame(WebSocketFrame.OP_BINARY, true, true, new byte[200]);
        CountingInputStream counting = new CountingInputStream(new ByteArrayInputStream(raw));
        WebSocketSession session = new WebSocketSession(counting, new ByteArrayOutputStream(), 256);

        assertTrue(session.readFrame(new WebSocketFrame()));

        // b0, b1, one bulk read for (2 extended-length + 4 mask) bytes, one bulk read for the
        // 200-byte payload: 4 total, independent of the payload size.
        assertTrue(counting.reads <= 4, "expected at most 4 underlying reads, was " + counting.reads);
    }
}
