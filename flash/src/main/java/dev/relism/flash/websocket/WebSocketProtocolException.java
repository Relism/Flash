package dev.relism.flash.websocket;

import java.io.IOException;

/**
 * A WebSocket frame violated RFC 6455 (bad opcode, wrong masking direction, oversized control
 * frame, fragmented control frame, or a message exceeding the session's buffer). Carries the
 * close code ({@code 1002} protocol error, {@code 1009} message too big) the session must send
 * before closing — see {@code WebSocketLoop}, the single site that catches this.
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
