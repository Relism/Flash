package dev.relism.flash.http2.frame;

import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2Exception;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** One test per RFC-mandated rejection, asserting the specific {@link Http2ErrorCode} — not merely that {@link Http2Exception} was thrown. */
class FrameValidatorTest {

    private static byte[] rawFrame(int length, int typeCode, int flags, int streamId) {
        byte[] buf = new byte[9];
        buf[0] = (byte) (length >>> 16);
        buf[1] = (byte) (length >>> 8);
        buf[2] = (byte) length;
        buf[3] = (byte) typeCode;
        buf[4] = (byte) flags;
        buf[5] = (byte) (streamId >>> 24);
        buf[6] = (byte) (streamId >>> 16);
        buf[7] = (byte) (streamId >>> 8);
        buf[8] = (byte) streamId;
        return buf;
    }

    private static FrameHeader headerOf(int length, FrameType type, int flags, int streamId) {
        byte[] buf = rawFrame(length, type.code(), flags, streamId);
        FrameHeader header = new FrameHeader();
        // reset() is package-private; same package as this test.
        header.reset(buf, 0);
        return header;
    }

    private static Http2ErrorCode codeOf(FrameHeader header, boolean insideHeaderBlock) {
        Http2Exception ex = assertThrows(Http2Exception.class, () -> FrameValidator.validate(header, insideHeaderBlock));
        return ex.errorCode();
    }

    // ── Length bounds, per type ──────────────────────────────────────────────

    @Test
    void ping_wrongLength_isFrameSizeError() {
        FrameHeader h = headerOf(7, FrameType.PING, 0, 0);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, codeOf(h, false));
    }

    @Test
    void rstStream_wrongLength_isFrameSizeError() {
        FrameHeader h = headerOf(3, FrameType.RST_STREAM, 0, 1);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, codeOf(h, false));
    }

    @Test
    void windowUpdate_wrongLength_isFrameSizeError() {
        FrameHeader h = headerOf(5, FrameType.WINDOW_UPDATE, 0, 1);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, codeOf(h, false));
    }

    @Test
    void priority_wrongLength_isFrameSizeError() {
        FrameHeader h = headerOf(4, FrameType.PRIORITY, 0, 1);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, codeOf(h, false));
    }

    @Test
    void goaway_tooShort_isFrameSizeError() {
        FrameHeader h = headerOf(7, FrameType.GOAWAY, 0, 0);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, codeOf(h, false));
    }

    @Test
    void goaway_exactlyEightBytes_isValid() {
        FrameHeader h = headerOf(8, FrameType.GOAWAY, 0, 0);
        assertDoesNotThrow(() -> FrameValidator.validate(h, false));
    }

    @Test
    void settings_notMultipleOfSix_isFrameSizeError() {
        FrameHeader h = headerOf(7, FrameType.SETTINGS, 0, 0);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, codeOf(h, false));
    }

    @Test
    void settings_multipleOfSix_isValid() {
        FrameHeader h = headerOf(12, FrameType.SETTINGS, 0, 0);
        assertDoesNotThrow(() -> FrameValidator.validate(h, false));
    }

    @Test
    void settings_zeroLength_isValid() {
        // An empty SETTINGS frame (0 entries) is legal -- e.g. the initial connection SETTINGS
        // with no non-default values, or a SETTINGS ACK.
        FrameHeader h = headerOf(0, FrameType.SETTINGS, FrameFlags.ACK, 0);
        assertDoesNotThrow(() -> FrameValidator.validate(h, false));
    }

    // ── Stream id rules ──────────────────────────────────────────────────────

    @Test
    void settings_nonZeroStreamId_isProtocolError() {
        FrameHeader h = headerOf(0, FrameType.SETTINGS, 0, 1);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, false));
    }

    @Test
    void ping_nonZeroStreamId_isProtocolError() {
        FrameHeader h = headerOf(8, FrameType.PING, 0, 3);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, false));
    }

    @Test
    void goaway_nonZeroStreamId_isProtocolError() {
        FrameHeader h = headerOf(8, FrameType.GOAWAY, 0, 5);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, false));
    }

    @Test
    void data_zeroStreamId_isProtocolError() {
        FrameHeader h = headerOf(0, FrameType.DATA, 0, 0);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, false));
    }

    @Test
    void headers_zeroStreamId_isProtocolError() {
        FrameHeader h = headerOf(0, FrameType.HEADERS, 0, 0);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, false));
    }

    @Test
    void rstStream_zeroStreamId_isProtocolError() {
        FrameHeader h = headerOf(4, FrameType.RST_STREAM, 0, 0);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, false));
    }

    @Test
    void windowUpdate_zeroStreamId_isValid_connectionWindow() {
        FrameHeader h = headerOf(4, FrameType.WINDOW_UPDATE, 0, 0);
        assertDoesNotThrow(() -> FrameValidator.validate(h, false));
    }

    @Test
    void windowUpdate_nonZeroStreamId_isValid_streamWindow() {
        FrameHeader h = headerOf(4, FrameType.WINDOW_UPDATE, 0, 9);
        assertDoesNotThrow(() -> FrameValidator.validate(h, false));
    }

    // ── PUSH_PROMISE from a client ───────────────────────────────────────────

    @Test
    void pushPromise_fromClient_isAlwaysProtocolError() {
        FrameHeader h = headerOf(4, FrameType.PUSH_PROMISE, 0, 1);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, false));
    }

    // ── Unknown frame types ──────────────────────────────────────────────────

    @Test
    void unknownType_outsideHeaderBlock_isIgnoredNotRejected() {
        byte[] buf = rawFrame(3, 0x20, 0, 1); // 0x20 is not a recognised type
        FrameHeader h = new FrameHeader();
        h.reset(buf, 0);
        assertNull(h.type());
        assertDoesNotThrow(() -> FrameValidator.validate(h, false));
    }

    @Test
    void unknownType_insideHeaderBlock_isProtocolError() {
        byte[] buf = rawFrame(3, 0x20, 0, 1);
        FrameHeader h = new FrameHeader();
        h.reset(buf, 0);
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, codeOf(h, true));
    }

    // ── Frame-size ceiling ────────────────────────────────────────────────────

    @Test
    void declaredLengthAboveMaxFrameSize_isFrameSizeError() {
        FrameHeader h = headerOf(dev.relism.flash.http2.Http2Limits.MAX_FRAME_SIZE_LOCAL + 1, FrameType.DATA, 0, 1);
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, codeOf(h, false));
    }
}
