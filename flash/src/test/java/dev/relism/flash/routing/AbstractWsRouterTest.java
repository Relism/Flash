package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.websocket.WebSocketHandler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AbstractWsRouterTest {

    static class DummyWsRouter extends AbstractWsRouter {
        WebSocketHandler lastHandler;
        HttpMethod lastMethod;
        String lastPath;

        @Override
        public WebSocketHandler route(Request request, Object scratch) { return null; }

        @Override
        protected AbstractWsRouter addRoute(HttpMethod method, String path, WebSocketHandler handler) {
            this.lastMethod = method;
            this.lastPath = path;
            this.lastHandler = handler;
            return this;
        }
    }

    @Test
    void register_sanitizesPathAndStoresHandler() {
        DummyWsRouter router = new DummyWsRouter();
        WebSocketHandler handler = new WebSocketHandler() {
            public void onOpen(dev.relism.flash.websocket.WebSocketSession session) {}
            public void onMessage(dev.relism.flash.websocket.WebSocketSession session, dev.relism.flash.websocket.WebSocketFrame frame) {}
        };

        router.register(HttpMethod.GET, "chat/", handler);

        assertEquals(HttpMethod.GET, router.lastMethod);
        assertEquals("/chat", router.lastPath);
        assertSame(handler, router.lastHandler);
    }
}
