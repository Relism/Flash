package dev.relism.flash.h2;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class Http2StreamExceptionTest {

    @Test
    void carriesStreamIdAndErrorCode() {
        Http2StreamException e = new Http2StreamException(7, Http2ErrorCode.STREAM_CLOSED, "closed");
        assertEquals(7, e.streamId());
        assertEquals(Http2ErrorCode.STREAM_CLOSED, e.errorCode());
        assertEquals("closed", e.getMessage());
    }

    @Test
    void stackTraceCaptureIsDisabled() {
        Http2StreamException e = new Http2StreamException(3, Http2ErrorCode.CANCEL, "cancelled");
        assertEquals(0, e.getStackTrace().length);
    }

    @Test
    void doesNotExtendIoException() {
        assertFalse(java.io.IOException.class.isAssignableFrom(Http2StreamException.class));
    }
}
