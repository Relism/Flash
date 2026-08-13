package dev.relism.flash.http2.frame;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.transport.BufferedByteSource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

class Http2FrameReaderTest {

    private static BufferedByteSource sourceOf(byte[] bytes) {
        return new BufferedByteSource(new ByteArrayInputStream(bytes), null);
    }

    private static byte[] buildFrame(FrameType type, int flags, int streamId, byte[] payload) {
        FrameWriteBuffer out = new FrameWriteBuffer(new ByteWriter(32));
        out.beginFrame(type, flags, streamId);
        out.writer().writeBytes(payload);
        out.endFrame();
        byte[] result = new byte[out.writer().length()];
        System.arraycopy(out.writer().array(), 0, result, 0, result.length);
        return result;
    }

    // ── Round trip every frame type ─────────────────────────────────────────

    @Test
    void roundTrip_everyFrameType() throws IOException {
        for (FrameType type : FrameType.values()) {
            int payloadLen = switch (type) {
                case PING -> 8;
                case RST_STREAM, WINDOW_UPDATE -> 4;
                case PRIORITY -> 5;
                case GOAWAY -> 8;
                default -> 10;
            };
            byte[] payload = new byte[payloadLen];
            for (int i = 0; i < payloadLen; i++) payload[i] = (byte) (i + 1);
            int streamId = type.streamIdRule() == FrameType.StreamIdRule.FORBIDDEN ? 0 : 7;

            byte[] wire = buildFrame(type, 0x1, streamId, payload);
            Http2FrameReader reader = new Http2FrameReader(sourceOf(wire));
            FrameHeader header = reader.readFrame();

            assertNotNull(header, "type=" + type);
            assertEquals(type, header.type());
            assertEquals(type.code(), header.typeCode());
            assertEquals(payloadLen, header.length());
            assertEquals(streamId, header.streamId());
            assertEquals(0x1, header.flags());
            for (int i = 0; i < payloadLen; i++) {
                assertEquals(payload[i], header.buffer()[header.payloadOffset() + i], "byte " + i + " of type " + type);
            }
            reader.consumeFrame();
        }
    }

    // ── Boundary lengths ─────────────────────────────────────────────────────

    @Test
    void boundaryLengths_0_1_16383_16384_16385() throws IOException {
        int[] lengths = {0, 1, 16383, 16384, 16385};
        for (int len : lengths) {
            byte[] payload = new byte[len];
            byte[] wire = buildFrame(FrameType.DATA, 0, 1, payload);
            Http2FrameReader reader = new Http2FrameReader(sourceOf(wire));
            if (len > dev.relism.flash.http2.Http2Limits.MAX_FRAME_SIZE_LOCAL) {
                Http2Exception ex = assertThrows(Http2Exception.class, reader::readFrame);
                assertEquals(dev.relism.flash.http2.Http2ErrorCode.FRAME_SIZE_ERROR, ex.errorCode());
            } else {
                FrameHeader header = reader.readFrame();
                assertNotNull(header);
                assertEquals(len, header.length());
            }
        }
    }

    // ── A frame split across multiple socket reads ─────────────────────────

    private static final class DribblingInputStream extends InputStream {
        private final byte[] data;
        private int pos;
        private final int chunkSize;

        DribblingInputStream(byte[] data, int chunkSize) {
            this.data = data;
            this.chunkSize = chunkSize;
        }

        @Override
        public int read() {
            return pos < data.length ? (data[pos++] & 0xFF) : -1;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            if (pos >= data.length) return -1;
            int n = Math.min(chunkSize, Math.min(len, data.length - pos));
            System.arraycopy(data, pos, dst, off, n);
            pos += n;
            return n;
        }
    }

    @Test
    void frameSplitAcrossThreeSocketReads() throws IOException {
        byte[] payload = new byte[300];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;
        byte[] wire = buildFrame(FrameType.DATA, 0, 3, payload);

        // 9(header) + 300(payload) = 309 bytes, dribbled in chunks of 103 -> 3 reads.
        int chunk = (wire.length + 2) / 3;
        BufferedByteSource src = new BufferedByteSource(new DribblingInputStream(wire, chunk), null);
        Http2FrameReader reader = new Http2FrameReader(src);
        FrameHeader header = reader.readFrame();

        assertNotNull(header);
        assertEquals(300, header.length());
        for (int i = 0; i < 300; i++) {
            assertEquals(payload[i], header.buffer()[header.payloadOffset() + i]);
        }
    }

    // ── A frame exactly filling the initial buffer ──────────────────────────

    @Test
    void frameExactlyFillingInitialBuffer() throws IOException {
        int bufSize = 64;
        byte[] payload = new byte[bufSize - 9]; // header + payload == bufSize exactly
        byte[] wire = buildFrame(FrameType.DATA, 0, 1, payload);
        assertEquals(bufSize, wire.length);

        Http2FrameReader reader = new Http2FrameReader(sourceOf(wire), bufSize);
        FrameHeader header = reader.readFrame();
        assertNotNull(header);
        assertEquals(payload.length, header.length());
    }

    // ── Multiple frames on one connection, sequential reads ─────────────────

    @Test
    void multipleFramesSequentially() throws IOException {
        ByteWriter w = new ByteWriter(64);
        FrameWriteBuffer out = new FrameWriteBuffer(w);
        out.beginFrame(FrameType.PING, 0, 0);
        out.writer().writeBytes(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        out.endFrame();
        out.beginFrame(FrameType.PING, dev.relism.flash.http2.frame.FrameFlags.ACK, 0);
        out.writer().writeBytes(new byte[]{8, 7, 6, 5, 4, 3, 2, 1});
        out.endFrame();
        byte[] wire = new byte[w.length()];
        System.arraycopy(w.array(), 0, wire, 0, wire.length);

        Http2FrameReader reader = new Http2FrameReader(sourceOf(wire));
        FrameHeader first = reader.readFrame();
        assertEquals(1, first.buffer()[first.payloadOffset()]);
        assertEquals(0, first.flags());
        reader.consumeFrame();

        FrameHeader second = reader.readFrame();
        assertEquals(8, second.buffer()[second.payloadOffset()]);
        assertEquals(FrameFlags.ACK, second.flags());
        reader.consumeFrame();

        assertNull(reader.readFrame()); // clean EOF after both frames consumed
    }

    // ── EOF handling ─────────────────────────────────────────────────────────

    @Test
    void cleanEofBetweenFrames_returnsNull() throws IOException {
        Http2FrameReader reader = new Http2FrameReader(sourceOf(new byte[0]));
        assertNull(reader.readFrame());
    }

    @Test
    void eofMidFrame_throwsEOFException() {
        byte[] wire = buildFrame(FrameType.DATA, 0, 1, new byte[100]);
        byte[] truncated = new byte[50]; // header + partial payload
        System.arraycopy(wire, 0, truncated, 0, 50);

        Http2FrameReader reader = new Http2FrameReader(sourceOf(truncated));
        assertThrows(EOFException.class, reader::readFrame);
    }

    @Test
    void eofMidHeader_throwsEOFException() {
        byte[] truncated = new byte[5]; // fewer than the 9 header bytes
        Http2FrameReader reader = new Http2FrameReader(sourceOf(truncated));
        assertThrows(EOFException.class, reader::readFrame);
    }

    // ── Reserved bit masking ─────────────────────────────────────────────────

    @Test
    void reservedBitInStreamId_isMaskedNotRejected() throws IOException {
        byte[] wire = buildFrame(FrameType.DATA, 0, 5, new byte[]{1, 2, 3});
        wire[5] |= (byte) 0x80; // set the reserved high bit of the stream-id field
        Http2FrameReader reader = new Http2FrameReader(sourceOf(wire));
        FrameHeader header = reader.readFrame();
        assertEquals(5, header.streamId(), "reserved bit must be masked, not folded into the stream id");
    }
}
