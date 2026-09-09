package dev.relism.flash.extension;

import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class FlashAppWebSocketTest {

    private FlashApp app;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        app = FlashApp.create(FlashConfiguration.builder()
                .port(0)
                .host("127.0.0.1")
                .build());
        port = app.port();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    @Test
    void ws_registersDirectEndpoint() {
        WebSocketHandler handler = new WebSocketHandler() {
            public void onOpen(WebSocketSession session) {}
            public void onMessage(WebSocketSession session, WebSocketFrame frame) {}
        };

        assertSame(app, app.ws("/chat", handler));
    }
}
