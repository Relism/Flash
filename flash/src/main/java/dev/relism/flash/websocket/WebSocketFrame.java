package dev.relism.flash.websocket;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** A zero-copy message view, valid only during {@code onMessage}. */
public final class WebSocketFrame {
    private byte[] array;
    private int offset;
    private int length;
    private byte opcode;

    void reset(WebSocketFraming.Session session) {
        array = session.array();
        offset = session.offset();
        length = session.length();
        opcode = session.opcode();
    }

    public boolean isText() { return opcode == WebSocketFraming.Session.TEXT; }
    public boolean isBinary() { return opcode == WebSocketFraming.Session.BINARY; }
    public byte[] array() { return array; }
    public int offset() { return offset; }
    public int length() { return length; }
    public String text() { return new String(array, offset, length, StandardCharsets.UTF_8); }
    public byte[] copy() { return Arrays.copyOfRange(array, offset, offset + length); }
}
