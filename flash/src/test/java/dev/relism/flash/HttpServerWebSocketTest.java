package dev.relism.flash;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerWebSocketTest {

    private FlashApp app;
    private int port;
    private final AtomicReference<Integer> closed = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        app = FlashApp.create(FlashConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .build());

        app.ws("/chat", new WebSocketHandler() {
            @Override
            public void onOpen(WebSocketSession session) {}

            @Override
            public void onMessage(WebSocketSession session, WebSocketFrame frame) {
                try {
                    if (frame.opcode() == WebSocketFrame.OP_TEXT) {
                        session.sendText(frame.copyPayload(), frame.payloadOffset(), frame.payloadLength());
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });

        app.ws("/close", new WebSocketHandler() {
            @Override public void onOpen(WebSocketSession session) {}
            @Override public void onMessage(WebSocketSession session, WebSocketFrame frame) {}
            @Override public void onClose(WebSocketSession session, int code) { closed.set(code); }
        });

        app.start();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private static final int SOCKET_TIMEOUT_MS = 5000;

    private static String handshakeKey() {
        return Base64.getEncoder().encodeToString("flash-test-key".getBytes(StandardCharsets.UTF_8));
    }

    private static String readHeaders(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b, prev3 = -1, prev2 = -1, prev1 = -1;
        while ((b = in.read()) != -1) {
            out.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
            prev3 = prev2; prev2 = prev1; prev1 = b;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static byte[] textFrame(String message) {
        byte[] payload = message.getBytes(StandardCharsets.UTF_8);
        byte[] frame = new byte[payload.length + 6];
        frame[0] = (byte) 0x81;
        frame[1] = (byte) (0x80 | payload.length);
        byte[] mask = {1, 2, 3, 4};
        System.arraycopy(mask, 0, frame, 2, 4);
        for (int i = 0; i < payload.length; i++) {
            frame[6 + i] = (byte) (payload[i] ^ mask[i & 3]);
        }
        return frame;
    }

    private static byte[] closeFrame(int code) {
        byte[] frame = new byte[8];
        frame[0] = (byte) 0x88;
        frame[1] = (byte) 0x82;
        byte[] mask = {1, 2, 3, 4};
        System.arraycopy(mask, 0, frame, 2, 4);
        frame[6] = (byte) (((code >> 8) & 0xFF) ^ mask[0]);
        frame[7] = (byte) ((code & 0xFF) ^ mask[1]);
        return frame;
    }

    @Test
    void websocket_upgrade_returns101AndEchoesText() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            String key = handshakeKey();
            String req = "GET /chat HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: keep-alive, Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + key + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n";
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String headers = readHeaders(in);
            assertTrue(headers.startsWith("HTTP/1.1 101 Switching Protocols"));
            assertTrue(headers.contains("Upgrade: websocket"));
            assertTrue(headers.contains("Connection: Upgrade"));
            assertTrue(headers.contains("Sec-WebSocket-Accept: "));

            out.write(textFrame("hello"));
            out.flush();

            byte[] frame = in.readNBytes(7);
            assertEquals((byte) 0x81, frame[0]);
            assertEquals((byte) 0x05, frame[1]);
            assertEquals("hello", new String(frame, 2, 5, StandardCharsets.UTF_8));
        }
    }

    @Test
    void websocket_ping_is_ponged() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            out.write(("GET /chat HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + handshakeKey() + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            readHeaders(in);

            out.write(new byte[] {(byte) 0x89, (byte) 0x80, 1, 2, 3, 4});
            out.flush();

            byte[] pong = in.readNBytes(2);
            assertEquals((byte) 0x8A, pong[0]);
            assertEquals((byte) 0x00, pong[1]);
        }
    }

    @Test
    void websocket_close_frame_closesSession() throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             InputStream in = socket.getInputStream();
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);

            out.write(("GET /close HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + handshakeKey() + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            readHeaders(in);

            out.write(closeFrame(1000));
            out.flush();

            Thread.sleep(100);
            assertEquals(1000, closed.get());
        }
    }
}
