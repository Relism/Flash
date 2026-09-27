package dev.relism.flash.testing;

import dev.relism.flash.App;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FlashWebSocketTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @RegisterExtension
    static FlashTest app = FlashTest.of(() -> App.create()
        .ws("/echo", new WebSocketHandler() {
            @Override public void onOpen(WebSocketSession session) { }
            @Override public void onMessage(WebSocketSession session, WebSocketFrame frame) {
                if (frame.opcode() != WebSocketFrame.OP_TEXT) return;
                try {
                    byte[] echo = ("echo:" + new String(frame.copyPayload(), frame.payloadOffset(),
                            frame.payloadLength(), StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
                    session.sendText(echo, 0, echo.length);
                } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                }
            }
        })
        .ws("/silent", new WebSocketHandler() {
            @Override public void onOpen(WebSocketSession session) { }
            @Override public void onMessage(WebSocketSession session, WebSocketFrame frame) { }
        }));

    @Test
    void roundTripsTextThroughARealHandshake() {
        try (FlashWebSocket socket = app.ws("/echo")) {
            socket.sendText("hello");
            assertEquals("echo:hello", socket.awaitText(TIMEOUT));
        }
    }

    @Test
    void queuesEveryMessageInOrder() {
        try (FlashWebSocket socket = app.ws("/echo")) {
            socket.sendText("one").sendText("two");
            assertEquals("echo:one", socket.awaitText(TIMEOUT));
            assertEquals("echo:two", socket.awaitText(TIMEOUT));
        }
    }

    /** The likeliest user mistake: teardown must cancel it rather than block on it. */
    @Test
    void anUnclosedSocketDoesNotHangTeardown() {
        FlashWebSocket leaked = app.ws("/echo");
        leaked.sendText("no try-with-resources here");
        assertEquals("echo:no try-with-resources here", leaked.awaitText(TIMEOUT));
    }

    @Test
    void awaitTextFailsLoudlyWhenNothingArrives() {
        try (FlashWebSocket socket = app.ws("/silent")) {
            socket.sendText("ignored");
            AssertionError error = assertThrows(AssertionError.class,
                    () -> socket.awaitText(Duration.ofMillis(300)));
            assertEquals("No WebSocket text message within PT0.3S", error.getMessage());
        }
    }
}
