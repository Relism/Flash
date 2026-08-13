package dev.relism.flash.h2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Http2ExceptionTest {

    @Test
    void ofCarriesTheGivenCodeAndMessage() {
        Http2Exception e = Http2Exception.of(Http2ErrorCode.FLOW_CONTROL_ERROR, "window exceeded");
        assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, e.errorCode());
        assertEquals("window exceeded", e.getMessage());
    }

    @Test
    void stackTraceCaptureIsDisabled() {
        Http2Exception e = Http2Exception.of(Http2ErrorCode.PROTOCOL_ERROR, "bad frame");
        assertEquals(0, e.getStackTrace().length);
    }

    @Test
    void singletonsCarryTheAdvertisedCode() {
        assertEquals(Http2ErrorCode.PROTOCOL_ERROR, Http2Exception.PROTOCOL_ERROR.errorCode());
        assertEquals(Http2ErrorCode.FRAME_SIZE_ERROR, Http2Exception.FRAME_SIZE_ERROR.errorCode());
        assertEquals(Http2ErrorCode.FLOW_CONTROL_ERROR, Http2Exception.FLOW_CONTROL_ERROR.errorCode());
        assertEquals(Http2ErrorCode.COMPRESSION_ERROR, Http2Exception.COMPRESSION_ERROR.errorCode());
        assertEquals(Http2ErrorCode.INTERNAL_ERROR, Http2Exception.INTERNAL_ERROR.errorCode());
        assertEquals(Http2ErrorCode.SETTINGS_TIMEOUT, Http2Exception.SETTINGS_TIMEOUT.errorCode());
    }

    @Test
    void doesNotExtendIoException() {
        assertFalse(java.io.IOException.class.isAssignableFrom(Http2Exception.class));
    }
}
