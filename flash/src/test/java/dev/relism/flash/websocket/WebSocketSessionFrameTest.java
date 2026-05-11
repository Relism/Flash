package dev.relism.flash.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketSessionFrameTest {

    @Test
    void readFrame_unmasksMaskedPayload() throws Exception {
        byte[] raw = new byte[] {
                (byte) 0x81,
                (byte) 0x85,
                1, 2, 3, 4,
                (byte) ('h' ^ 1),
                (byte) ('i' ^ 2),
                (byte) ('!' ^ 3),
                (byte) ('!' ^ 4),
                (byte) ('?' ^ 1)
        };

        WebSocketSession session = new WebSocketSession(new ByteArrayInputStream(raw), new ByteArrayOutputStream(), 16);
        WebSocketFrame frame = new WebSocketFrame();

        assertTrue(session.readFrame(frame));
        assertEquals(WebSocketFrame.OP_TEXT, frame.opcode());
        assertTrue(frame.isFin());
        assertEquals(5, frame.payloadLength());
        assertEquals("hi!!?", new String(frame.copyPayload(), frame.payloadOffset(), frame.payloadLength()));
    }

    @Test
    void readFrame_rejectsOversizedPayload() {
        byte[] raw = new byte[] {(byte) 0x82, (byte) 0x7E, 0x01, 0x00};
        WebSocketSession session = new WebSocketSession(new ByteArrayInputStream(raw), new ByteArrayOutputStream(), 8);
        WebSocketFrame frame = new WebSocketFrame();

        assertThrows(Exception.class, () -> session.readFrame(frame));
    }
}
