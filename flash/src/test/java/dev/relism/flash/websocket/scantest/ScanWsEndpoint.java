package dev.relism.flash.websocket.scantest;

import dev.relism.flash.routing.Ws;
import dev.relism.flash.websocket.WebSocketEndpoint;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketSession;

@Ws("/ws")
public class ScanWsEndpoint extends WebSocketEndpoint {

    @Override
    public void onOpen(WebSocketSession session) {}

    @Override
    public void onMessage(WebSocketSession session, WebSocketFrame frame) {
        try {
            session.sendText(frame.copyPayload(), frame.payloadOffset(), frame.payloadLength());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
