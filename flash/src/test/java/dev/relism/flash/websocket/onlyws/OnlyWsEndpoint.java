package dev.relism.flash.websocket.onlyws;

import dev.relism.flash.routing.Ws;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;

@Ws("/only")
public class OnlyWsEndpoint extends WebSocketHandler {

    @Override
    protected void onMessage(WebSocketSession session, WebSocketFrame frame) throws Exception {
        session.echo(frame);
    }
}
