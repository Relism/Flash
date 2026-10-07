package dev.relism.flash.websocket;

import java.io.IOException;

/**
 * A WebSocket frame violated RFC 6455 (bad opcode, unmasked client frame, oversized or fragmented
 * control frame, text that is not UTF-8, or a message larger than {@code wsFrameBufferSize}).
 * Carries the close code ({@code 1002} protocol error, {@code 1007} bad data, {@code 1009} message
 * too big) the channel closes with.
 */
public final class WebSocketProtocolException extends IOException {

    private final int closeCode;

    public WebSocketProtocolException(int closeCode, String message) {
        super(message);
        this.closeCode = closeCode;
    }

    public int closeCode() {
        return closeCode;
    }
}
