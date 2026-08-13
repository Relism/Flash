package dev.relism.flash.websocket;

import java.io.IOException;

/**
 * Drives one {@link WebSocketSession}'s read loop until the session closes, dispatching frames
 * responsibility is this loop; the handshake and upgrade detection live in
 * {@link WebSocketUpgrade}.
 */
public final class WebSocketLoop {

    private WebSocketLoop() {
    }

    public static void run(WebSocketSession session, WebSocketHandler handler) {
        handler.onOpen(session);
        WebSocketFrame frame = new WebSocketFrame();
        try {
            while (session.isOpen()) {
                if (!session.readFrame(frame)) break;
                switch (frame.opcode()) {
                    case WebSocketFrame.OP_TEXT, WebSocketFrame.OP_BINARY
                            -> handler.onMessage(session, frame);
                    case WebSocketFrame.OP_CLOSE
                            -> session.closeFromPeer(frame);
                    case WebSocketFrame.OP_PING
                            -> session.sendPong(frame);
                    case WebSocketFrame.OP_PONG -> { /* heartbeat ack, no-op */ }
                }
            }
        } catch (WebSocketProtocolException e) {
            try { session.close(e.closeCode()); } catch (IOException ignored) { }
            handler.onError(session, e);
        } catch (IOException e) {
            handler.onError(session, e);
        } finally {
            handler.onClose(session, session.closeCode());
            session.forceClose();
        }
    }
}
