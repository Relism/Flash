package dev.relism.flash.h2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Http2ErrorCodeTest {

    @Test
    void everyCodeRoundTripsThroughFromCode() {
        for (Http2ErrorCode code : Http2ErrorCode.values()) {
            assertSame(code, Http2ErrorCode.fromCode(code.code()));
        }
    }

    @Test
    void bytesAreFourByteBigEndian() {
        for (Http2ErrorCode code : Http2ErrorCode.values()) {
            assertEquals(4, code.bytes().length, code.name());
            int decoded = ((code.bytes()[0] & 0xFF) << 24)
                    | ((code.bytes()[1] & 0xFF) << 16)
                    | ((code.bytes()[2] & 0xFF) << 8)
                    | (code.bytes()[3] & 0xFF);
            assertEquals(code.code(), decoded, code.name());
        }
    }

    @Test
    void allFourteenRfc9113CodesArePresent() {
        assertEquals(14, Http2ErrorCode.values().length);
        assertEquals(0x00, Http2ErrorCode.NO_ERROR.code());
        assertEquals(0x01, Http2ErrorCode.PROTOCOL_ERROR.code());
        assertEquals(0x02, Http2ErrorCode.INTERNAL_ERROR.code());
        assertEquals(0x03, Http2ErrorCode.FLOW_CONTROL_ERROR.code());
        assertEquals(0x04, Http2ErrorCode.SETTINGS_TIMEOUT.code());
        assertEquals(0x05, Http2ErrorCode.STREAM_CLOSED.code());
        assertEquals(0x06, Http2ErrorCode.FRAME_SIZE_ERROR.code());
        assertEquals(0x07, Http2ErrorCode.REFUSED_STREAM.code());
        assertEquals(0x08, Http2ErrorCode.CANCEL.code());
        assertEquals(0x09, Http2ErrorCode.COMPRESSION_ERROR.code());
        assertEquals(0x0a, Http2ErrorCode.CONNECT_ERROR.code());
        assertEquals(0x0b, Http2ErrorCode.ENHANCE_YOUR_CALM.code());
        assertEquals(0x0c, Http2ErrorCode.INADEQUATE_SECURITY.code());
        assertEquals(0x0d, Http2ErrorCode.HTTP_1_1_REQUIRED.code());
    }

    @Test
    void unknownCodeReturnsNull() {
        assertNull(Http2ErrorCode.fromCode(0x0e));
        assertNull(Http2ErrorCode.fromCode(-1));
        assertNull(Http2ErrorCode.fromCode(Integer.MAX_VALUE));
    }

    @Test
    void bytesInstanceIsStablePerConstant() {
        // Precomputed at class init (R4) — must not be rebuilt per call.
        assertSame(Http2ErrorCode.PROTOCOL_ERROR.bytes(), Http2ErrorCode.PROTOCOL_ERROR.bytes());
    }
}
