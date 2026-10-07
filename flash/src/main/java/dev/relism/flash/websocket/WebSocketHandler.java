package dev.relism.flash.websocket;

import dev.relism.flash.ServerConfig;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.DuplexHandler;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

/** A bare WebSocket endpoint with one reused frame view per connection. */
public abstract class WebSocketHandler extends DuplexHandler {
    private int maxMessage;

    @Override
    protected final void onInit() {
        maxMessage = services().get(ServerConfig.class).getWsFrameBufferSize();
        onWebSocketInit();
    }

    protected void onWebSocketInit() {}
    protected void onOpen(WebSocketSession session) throws Exception {}
    protected abstract void onMessage(WebSocketSession session, WebSocketFrame frame) throws Exception;
    protected void onClose(WebSocketSession session, int code, String reason) {}
    protected void onError(WebSocketSession session, Throwable failure) {}

    @Override
    protected final void prepare(Request request, Response response) {
        WebSocketFraming.prepare(request, response);
    }

    @Override
    protected final boolean switchesProtocol(Request request) {
        return request.connectProtocol() == null;
    }

    @Override
    protected final void handle(Request request, Response response, Duplex duplex) throws Exception {
        WebSocketFraming.Session engine = WebSocketFraming.open(duplex, maxMessage);
        WebSocketSession session = new WebSocketSession(request, engine);
        WebSocketFrame frame = new WebSocketFrame();
        try {
            onOpen(session);
            while (engine.next()) {
                frame.reset(engine);
                onMessage(session, frame);
            }
            onClose(session, engine.closeCode(), engine.closeReason());
        } catch (Throwable failure) {
            engine.close(failure);
            onError(session, failure);
        } finally {
            engine.close(null);
        }
    }
}
