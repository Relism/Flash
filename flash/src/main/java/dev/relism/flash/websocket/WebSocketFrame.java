package dev.relism.flash.websocket;

/**
 * Zero-copy view over {@link WebSocketSession}'s read buffer.
 * One instance allocated per connection, reset per frame — zero steady-state allocation.
 *
 * <p><b>Lifetime contract:</b> valid ONLY within the synchronous scope of
 * {@link WebSocketHandler#onMessage(WebSocketSession, WebSocketFrame)}.
 * The underlying buffer is owned by {@link WebSocketSession} and will be
 * overwritten on the next frame read. Never retain a reference beyond the
 * callback; use {@link #copyPayload()} when data must outlive the call.
 *
 * <p><b>Debug safety:</b> {@link #buffer()} is package-private to prevent
 * accidental cross-boundary access. External consumers must go through
 * {@link #copyPayload()} or process in-place within the callback.
 */
public final class WebSocketFrame {

    public static final byte OP_CONTINUATION = 0x0;
    public static final byte OP_TEXT         = 0x1;
    public static final byte OP_BINARY       = 0x2;
    public static final byte OP_CLOSE        = 0x8;
    public static final byte OP_PING         = 0x9;
    public static final byte OP_PONG         = 0xA;

    private byte[]  buf;
    private int     payloadOff;
    private int     payloadLen;
    private byte    opcode;
    private boolean fin;

    // Monotonically incremented on every reset. Allows callers that hold a
    // reference beyond onMessage() to detect stale access in assertions/tests.
    private int generation;

    /** Called by {@link WebSocketSession} only. */
    void reset(byte[] buf, int off, int len, byte opcode, boolean fin) {
        this.buf        = buf;
        this.payloadOff = off;
        this.payloadLen = len;
        this.opcode     = opcode;
        this.fin        = fin;
        this.generation++;
    }

    public byte    opcode()        { return opcode; }
    public boolean isFin()         { return fin; }
    public int     payloadOffset() { return payloadOff; }
    public int     payloadLength() { return payloadLen; }

    /**
     * Returns the generation counter at the moment of this call.
     * Store it at callback entry and compare later to detect stale retention:
     * <pre>{@code
     *   int gen = frame.generation();
     *   executor.submit(() -> {
     *       assert frame.generation() == gen : "frame buffer was recycled!";
     *   });
     * }</pre>
     */
    public int generation() { return generation; }

    /**
     * Direct reference to the session read buffer — package-private to prevent
     * accidental retention outside the websocket package.
     * External callers: use {@link #copyPayload()}.
     */
    byte[] buffer() { return buf; }

    /**
     * Copies the payload into a fresh array. Allocates — O(n) in payload size.
     * Use only when data must outlive the {@code onMessage} callback or be
     * handed off to another thread. For payloads > a few KB consider wrapping
     * the result in a pooled {@link java.nio.ByteBuffer} to avoid GC pressure.
     */
    public byte[] copyPayload() {
        byte[] copy = new byte[payloadLen];
        System.arraycopy(buf, payloadOff, copy, 0, payloadLen);
        return copy;
    }
}