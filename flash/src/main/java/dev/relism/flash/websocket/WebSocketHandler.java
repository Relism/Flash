package dev.relism.flash.websocket;

/**
 * Handler for a WebSocket endpoint. Lifecycle:
 * onOpen → onMessage* → onClose | onError.
 *
 * Lifetime contract for {@link WebSocketFrame}: valid ONLY within
 * the synchronous scope of {@link #onMessage}. Do not retain references
 * to the frame or its buffer across calls — copy with {@link WebSocketFrame#copyPayload()}
 * if data must outlive the callback.
 *
 * OP_CONTINUATION frames are delivered as-is; fragment reassembly is an
 * application concern.
 */
public interface WebSocketHandler {
    void onOpen(WebSocketSession session);
    void onMessage(WebSocketSession session, WebSocketFrame frame);
    default void onClose(WebSocketSession session, int code) {}
    default void onError(WebSocketSession session, Throwable t) {}
}
