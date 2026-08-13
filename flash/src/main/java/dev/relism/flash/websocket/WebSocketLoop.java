package dev.relism.flash.websocket;

import java.io.IOException;

/**
 * Drives one {@link WebSocketSession}'s read loop until the session closes. The handshake and
 * upgrade detection live in {@link WebSocketUpgrade}.
 */
public final class WebSocketLoop {

  private WebSocketLoop() {}

  public static void run(WebSocketSession session, WebSocketHandler handler) {
    WebSocketFrame frame = new WebSocketFrame();
    try {
      handler.onOpen(session);
      while (session.isOpen()) {
        if (!session.readFrame(frame)) break;
        switch (frame.opcode()) {
          case WebSocketFrame.OP_TEXT, WebSocketFrame.OP_BINARY ->
              handler.onMessage(session, frame);
          case WebSocketFrame.OP_CLOSE -> session.closeFromPeer(frame);
          case WebSocketFrame.OP_PING -> session.sendPong(frame);
          case WebSocketFrame.OP_PONG -> {
            // Heartbeat acknowledgement; no action is required.
          }
        }
      }
    } catch (WebSocketProtocolException failure) {
      try {
        session.close(failure.closeCode());
      } catch (IOException ignored) {
        // The peer may already have closed the transport.
      }
      handler.onError(session, failure);
    } catch (IOException | RuntimeException failure) {
      handler.onError(session, failure);
    } finally {
      try {
        handler.onClose(session, session.closeCode());
      } finally {
        session.forceClose();
      }
    }
  }
}
