package dev.relism.flash.websocket;

/**
 * A WebSocket endpoint: {@code onOpen}, then {@code onMessage} for every frame, then
 * {@code onClose} or {@code onError}. A class the app builds takes its services through its
 * constructor, like a handler.
 *
 * <p>A {@link WebSocketFrame} is valid only inside the {@code onMessage} call that received it:
 * copy what must outlive it with {@link WebSocketFrame#copyPayload()}. A fragmented message is
 * reassembled by the session, so {@code onMessage} is handed a whole one and never a fragment.
 */
public interface WebSocketHandler {

    void onOpen(WebSocketSession session);

    void onMessage(WebSocketSession session, WebSocketFrame frame);

    default void onClose(WebSocketSession session, int code) {}

    default void onError(WebSocketSession session, Throwable t) {}

    /** Runs once at boot, after construction and before the first connection. */
    default void onInit() {}
}
