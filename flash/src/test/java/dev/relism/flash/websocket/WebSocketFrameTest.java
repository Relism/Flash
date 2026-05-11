package dev.relism.flash.websocket;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketFrameTest {

    @Test
    void copyPayload_copiesActiveSlice() {
        WebSocketFrame frame = new WebSocketFrame();
        byte[] buf = "hello".getBytes();
        frame.reset(buf, 1, 3, WebSocketFrame.OP_TEXT, true);

        byte[] copy = frame.copyPayload();

        assertArrayEquals("ell".getBytes(), copy);
        assertNotSame(buf, copy);
        assertEquals(WebSocketFrame.OP_TEXT, frame.opcode());
        assertTrue(frame.isFin());
        assertEquals(1, frame.payloadOffset());
        assertEquals(3, frame.payloadLength());
    }
}
