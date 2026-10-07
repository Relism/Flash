package dev.relism.flash.websocket;

import dev.relism.flash.http.Request;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** One accepted WebSocket connection. Sending is safe from any thread. */
public final class WebSocketSession {
    private final Request request;
    private final WebSocketFraming.Session session;

    WebSocketSession(Request request, WebSocketFraming.Session session) {
        this.request = request;
        this.session = session;
    }

    public Request request() { return request; }
    public boolean isOpen() { return session.isOpen(); }
    public int closeCode() { return session.closeCode(); }
    public String closeReason() { return session.closeReason(); }

    public void sendText(String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        sendText(bytes, 0, bytes.length);
    }

    public void sendText(byte[] utf8, int offset, int length) throws IOException {
        session.send(WebSocketFraming.Session.TEXT, utf8, offset, length);
    }

    public void send(byte[] bytes, int offset, int length) throws IOException {
        session.send(WebSocketFraming.Session.BINARY, bytes, offset, length);
    }

    public void echo(WebSocketFrame frame) throws IOException {
        session.send(frame.isText() ? WebSocketFraming.Session.TEXT : WebSocketFraming.Session.BINARY,
                frame.array(), frame.offset(), frame.length());
    }

    public void close(int code) throws IOException {
        session.close(code, "");
    }

    public void close(int code, String reason) throws IOException {
        session.close(code, reason);
    }

    /** Waits until either peer has completed the closing handshake. */
    public void await() throws InterruptedException {
        session.await();
    }
}
