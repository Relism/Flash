package dev.relism.flash.websocket;

import dev.relism.flash.extension.FlashContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketEndpointTest {

    static class DummyEndpoint extends WebSocketEndpoint {
        boolean initCalled;

        @Override
        protected void onInit() {
            initCalled = true;
        }

        @Override
        public void onOpen(WebSocketSession session) {}

        @Override
        public void onMessage(WebSocketSession session, WebSocketFrame frame) {}
    }

    @Test
    void bind_callsOnInit() {
        DummyEndpoint endpoint = new DummyEndpoint();

        endpoint.bind(new FlashContext());

        assertTrue(endpoint.initCalled);
    }

    @Test
    void requireBeforeBind_throws() {
        DummyEndpoint endpoint = new DummyEndpoint();

        assertThrows(IllegalStateException.class, () -> endpoint.require(String.class));
    }
}
