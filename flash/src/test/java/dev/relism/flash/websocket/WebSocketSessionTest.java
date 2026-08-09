package dev.relism.flash.websocket;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.HeaderMap;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestLine;
import dev.relism.fpr.core.ByteView;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketSessionTest {

    private static ByteView viewOf(String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        return new ByteView() {
            public int length() { return bytes.length; }
            public byte byteAt(int idx) { return bytes[idx]; }
        };
    }

    @Test
    void request_returnsWhatWasPassedToConstructor() {
        RequestLine line = new RequestLine(HttpMethod.GET, viewOf("/chat"), null, viewOf("HTTP/1.1"), new HeaderMap());
        Request req = new Request(line, new byte[0]);
        WebSocketSession session = new WebSocketSession(
                new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 64, req, false);

        assertSame(req, session.request());
    }

    @Test
    void request_defaultsToNullOnThreeArgConstructor() {
        WebSocketSession session = new WebSocketSession(new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 64);

        assertNull(session.request());
    }

    @Test
    void sendText_masksWhenActingAsClient() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketSession session = new WebSocketSession(
                new ByteArrayInputStream(new byte[0]), out, 64, null, true);

        byte[] payload = "hi".getBytes();
        session.sendText(payload, 0, payload.length);

        byte[] bytes = out.toByteArray();
        assertEquals((byte) 0x81, bytes[0]); // FIN + TEXT
        assertEquals((byte) (0x80 | 2), bytes[1]); // masked bit + length 2
        byte m0 = bytes[2], m1 = bytes[3], m2 = bytes[4], m3 = bytes[5];
        assertEquals((byte) ('h' ^ m0), bytes[6]);
        assertEquals((byte) ('i' ^ m1), bytes[7]);
        // The caller's buffer is mutated in place by the mask (documented, zero-copy tradeoff).
        assertEquals((byte) ('h' ^ m0), payload[0]);
    }

    @Test
    void sendText_doesNotMaskWhenActingAsServer() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        WebSocketSession session = new WebSocketSession(new ByteArrayInputStream(new byte[0]), out, 64);

        byte[] payload = "hi".getBytes();
        session.sendText(payload, 0, payload.length);

        byte[] bytes = out.toByteArray();
        assertEquals((byte) 0x81, bytes[0]);
        assertEquals((byte) 2, bytes[1]); // no masked bit
        assertEquals('h', bytes[2]);
        assertEquals('i', bytes[3]);
    }

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
