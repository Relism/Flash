package dev.relism.flash.websocket;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketSessionTest {

    @Test
    void close_setsClosedAndWritesFrame() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketSession session = new WebSocketSession(new ByteArrayInputStream(new byte[0]), out, 64);

        session.close(1000);

        assertFalse(session.isOpen());
        assertEquals(1000, session.closeCode());
        byte[] bytes = out.toByteArray();
        assertEquals((byte) 0x88, bytes[0]);
        assertEquals((byte) 0x02, bytes[1]);
        assertEquals((byte) 0x03, bytes[2]);
        assertEquals((byte) 0xE8, bytes[3]);
    }

    @Test
    void closeFromPeer_extractsCloseCode() {
        WebSocketSession session = new WebSocketSession(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 64);
        WebSocketFrame frame = new WebSocketFrame();
        frame.reset(new byte[] {(byte) 0x03, (byte) 0xE8}, 0, 2, WebSocketFrame.OP_CLOSE, true);

        session.closeFromPeer(frame);

        assertFalse(session.isOpen());
        assertEquals(1000, session.closeCode());
    }
}
