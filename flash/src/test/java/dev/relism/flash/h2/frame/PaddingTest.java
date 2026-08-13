package dev.relism.flash.h2.frame;

import dev.relism.flash.h2.Http2ErrorCode;
import dev.relism.flash.h2.Http2Exception;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PaddingTest {

    @Test
    void notPadded_returnsWholePayloadUnchanged() {
        byte[] buf = {1, 2, 3, 4, 5};
        long r = Padding.unpad(buf, 1, 4, false);
        assertEquals(1, Padding.dataOffset(r));
        assertEquals(4, Padding.dataLength(r));
    }

    @Test
    void padded_zeroPadLength_allBytesAreData() {
        // [padLength=0][data...]
        byte[] buf = {0, 10, 20, 30};
        long r = Padding.unpad(buf, 0, 4, true);
        assertEquals(1, Padding.dataOffset(r));
        assertEquals(3, Padding.dataLength(r));
        assertEquals(10, buf[Padding.dataOffset(r)]);
    }

    @Test
    void padded_someData_somePadding() {
        // [padLength=2][data: 3 bytes][padding: 2 bytes] -> payload length 6
        byte[] buf = {2, 7, 8, 9, 0, 0};
        long r = Padding.unpad(buf, 0, 6, true);
        assertEquals(1, Padding.dataOffset(r));
        assertEquals(3, Padding.dataLength(r));
        assertEquals(7, buf[Padding.dataOffset(r)]);
        assertEquals(9, buf[Padding.dataOffset(r) + 2]);
    }

    @Test
    void padded_allPaddingNoData() {
        // [padLength=3][padding x3] -> payload length 4, dataLength 0
        byte[] buf = {3, 0, 0, 0};
        long r = Padding.unpad(buf, 0, 4, true);
        assertEquals(0, Padding.dataLength(r));
    }

    @Test
    void padded_atNonZeroOffset_withinLargerBuffer() {
        byte[] buf = {(byte) 0xFF, (byte) 0xFF, 1, 5, 6, 0, (byte) 0xFF};
        // payload starts at index 2, length 4: [padLength=1][data:5,6][padding:1]
        long r = Padding.unpad(buf, 2, 4, true);
        assertEquals(3, Padding.dataOffset(r));
        assertEquals(2, Padding.dataLength(r));
        assertEquals(5, buf[Padding.dataOffset(r)]);
        assertEquals(6, buf[Padding.dataOffset(r) + 1]);
    }

    @Test
    void padded_zeroPayloadLength_isProtocolError() {
        byte[] buf = {};
        Http2Exception ex = assertThrows(Http2Exception.class, () -> Padding.unpad(buf, 0, 0, true));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ex.errorCode());
    }

    @Test
    void padded_padLengthEqualsPayloadLength_isProtocolError() {
        // payloadLength=3, claimed padLength=3 -- leaves -1 bytes for data, invalid.
        byte[] buf = {3, 0, 0};
        Http2Exception ex = assertThrows(Http2Exception.class, () -> Padding.unpad(buf, 0, 3, true));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ex.errorCode());
    }

    @Test
    void padded_padLengthGreaterThanPayloadLength_isProtocolError() {
        byte[] buf = {(byte) 255, 0, 0};
        Http2Exception ex = assertThrows(Http2Exception.class, () -> Padding.unpad(buf, 0, 3, true));
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, ex.errorCode());
    }

    @Test
    void padded_maxValidPadLength_leavesZeroData() {
        // payloadLength=5: [padLength=4][padding x4] -- valid, dataLength 0.
        byte[] buf = {4, 0, 0, 0, 0};
        long r = Padding.unpad(buf, 0, 5, true);
        assertEquals(0, Padding.dataLength(r));
    }
}
