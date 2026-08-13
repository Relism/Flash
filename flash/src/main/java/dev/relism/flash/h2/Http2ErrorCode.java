package dev.relism.flash.h2;

/**
 * The 14 HTTP/2 error codes defined by RFC 9113 §7.
 *
 * <p>Each constant carries its 4-byte big-endian wire encoding, precomputed once at class
 * load (RFC 9113 §6.4 {@code RST_STREAM} and §6.8 {@code GOAWAY} both carry the error code as
 * a raw 32-bit field — there is no framing around it to build). Callers write
 * {@link #bytes()} directly into a frame payload; nothing is formatted at request time (R4).
 *
 * <p>{@code Http2ErrorCode} is used to reject a peer <em>and</em> to interpret what a peer
 * sends us: {@link #fromCode(int)} decodes a received 32-bit value. RFC 9113 does not reserve
 * unknown codes for future use in a way that requires us to accept them silently as one of the
 * 14 — an endpoint that receives an error code it does not recognise treats it as
 * {@code INTERNAL_ERROR}-equivalent for logging purposes; {@link #fromCode(int)} returns
 * {@code null} for that case and callers log the raw integer rather than guessing a mapping.
 */
public enum Http2ErrorCode {

    /** Graceful shutdown or successful completion; not an error. RFC 9113 §7. */
    NO_ERROR(0x00),
    /** The peer violated the protocol in a way not covered by a more specific code. */
    PROTOCOL_ERROR(0x01),
    /** Unexpected internal condition on our side (e.g. an uncaught exception on the demux loop). */
    INTERNAL_ERROR(0x02),
    /** A flow-control window was violated: overflow past 2^31-1, or a peer exceeded its window. */
    FLOW_CONTROL_ERROR(0x03),
    /** The peer did not acknowledge our SETTINGS within {@code SETTINGS_ACK_TIMEOUT_MS}. */
    SETTINGS_TIMEOUT(0x04),
    /** A frame was received for a stream that is already closed. */
    STREAM_CLOSED(0x05),
    /** A frame's length did not match what its type requires (RFC 9113 §4.2, per-type rules). */
    FRAME_SIZE_ERROR(0x06),
    /** The stream was refused before any processing; safe for the client to retry elsewhere. */
    REFUSED_STREAM(0x07),
    /** Used by clients to cancel a stream; Flash never sends it, only receives it. */
    CANCEL(0x08),
    /** An HPACK decoding failure. Terminates the connection because the dynamic table state is lost. */
    COMPRESSION_ERROR(0x09),
    /** A CONNECT-tunnelled stream failed. */
    CONNECT_ERROR(0x0a),
    /** The peer is generating excessive load (rate-limit rejection: Rapid Reset, PING/SETTINGS floods). */
    ENHANCE_YOUR_CALM(0x0b),
    /** The negotiated TLS parameters fall below RFC 9113 §9.2's minimum security requirements. */
    INADEQUATE_SECURITY(0x0c),
    /** Defined by RFC 9113 for HTTP/1.1-only resources; Flash serves everything over h2, so unused. */
    HTTP_1_1_REQUIRED(0x0d);

    private static final Http2ErrorCode[] BY_CODE = new Http2ErrorCode[values().length];

    static {
        for (Http2ErrorCode c : values()) {
            BY_CODE[c.code] = c;
        }
    }

    private final int code;
    private final byte[] bytes;

    Http2ErrorCode(int code) {
        this.code = code;
        this.bytes = new byte[]{
                (byte) (code >>> 24),
                (byte) (code >>> 16),
                (byte) (code >>> 8),
                (byte) code
        };
    }

    /** The numeric error code as it appears on the wire. */
    public int code() {
        return code;
    }

    /**
     * The pre-encoded 4-byte big-endian wire form. Safe to write directly into a
     * {@code RST_STREAM} or {@code GOAWAY} payload with a single {@code System.arraycopy} —
     * never allocated or formatted per use.
     */
    public byte[] bytes() {
        return bytes;
    }

    /**
     * Decodes a 32-bit error code received from a peer. Returns {@code null} for a value
     * outside the 14 defined codes; the caller should log the raw integer rather than assume
     * a mapping, since RFC 9113 permits future extension codes we do not yet know about.
     */
    public static Http2ErrorCode fromCode(int code) {
        if (code >= 0 && code < BY_CODE.length) {
            return BY_CODE[code];
        }
        return null;
    }
}
