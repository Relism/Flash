package dev.relism.flash.h2.frame;

/**
 * The 10 HTTP/2 frame types (RFC 9113 §6), plus the shared per-type validation rules
 * {@link FrameValidator} enforces. Values above {@code 0x9} are not assigned a constant here —
 * RFC 9113 §4.1 requires unknown types to be silently ignored (read and discard the payload),
 * which {@link Http2FrameReader}'s caller implements by checking {@code type >
 * FrameType.maxKnown()} rather than by this enum growing an {@code UNKNOWN} member (an
 * {@code UNKNOWN} constant would misleadingly suggest "a recognised category of unrecognised
 * frame", when the correct handling is simply "not this table, skip it").
 *
 * <h3>Per-type validation, table-driven (R4)</h3>
 * Each constant carries the RFC-mandated payload length bounds, whether a zero stream id is
 * required/forbidden/either, and whether the frame counts toward the CONTINUATION-flood guard
 * ({@code EX}-style defence, {@code Http2Limits#MAX_CONTINUATION_FRAMES_PER_BLOCK}) — see
 * {@link FrameValidator} for how these are applied and the specific RFC citation per rule.
 */
public enum FrameType {
    /** RFC 9113 §6.1. Stream body bytes. Stream id required. Length: 0..MAX_FRAME_SIZE. */
    DATA(0x0, 0, Integer.MAX_VALUE, StreamIdRule.REQUIRED),
    /** RFC 9113 §6.2. Header block fragment (HPACK). Stream id required. */
    HEADERS(0x1, 0, Integer.MAX_VALUE, StreamIdRule.REQUIRED),
    /** RFC 9113 §6.3. Deprecated priority signal — parsed and discarded, never acted on (DEC, Phase 5 task 7). */
    PRIORITY(0x2, 5, 5, StreamIdRule.REQUIRED),
    /** RFC 9113 §6.4. Stream-level error. Exactly 4 bytes (the error code). Stream id required. */
    RST_STREAM(0x3, 4, 4, StreamIdRule.REQUIRED),
    /** RFC 9113 §6.5. Connection-level parameters. Length must be a multiple of 6. Stream id must be 0. */
    SETTINGS(0x4, 0, Integer.MAX_VALUE, StreamIdRule.FORBIDDEN),
    /** RFC 9113 §6.6. Never sent (Flash advertises {@code SETTINGS_ENABLE_PUSH=0}); receiving one from a client is a protocol error. */
    PUSH_PROMISE(0x5, 4, Integer.MAX_VALUE, StreamIdRule.REQUIRED),
    /** RFC 9113 §6.7. Connection liveness / RTT probe. Exactly 8 bytes of opaque data. Stream id must be 0. */
    PING(0x6, 8, 8, StreamIdRule.FORBIDDEN),
    /** RFC 9113 §6.8. Connection shutdown notice. At least 8 bytes (last-stream-id + error code). Stream id must be 0. */
    GOAWAY(0x7, 8, Integer.MAX_VALUE, StreamIdRule.FORBIDDEN),
    /** RFC 9113 §6.9. Flow-control window increment. Exactly 4 bytes. Stream id may be either (0 = connection window). */
    WINDOW_UPDATE(0x8, 4, 4, StreamIdRule.EITHER),
    /** RFC 9113 §6.10. Continuation of a header block that did not fit one HEADERS/PUSH_PROMISE frame. Stream id required. */
    CONTINUATION(0x9, 0, Integer.MAX_VALUE, StreamIdRule.REQUIRED);

    /** Whether a frame type requires stream id 0, requires it non-zero, or permits either. */
    public enum StreamIdRule { REQUIRED, FORBIDDEN, EITHER }

    private static final FrameType[] BY_CODE = new FrameType[values().length];

    static {
        for (FrameType t : values()) {
            BY_CODE[t.code] = t;
        }
    }

    private final int code;
    private final int minLength;
    private final int maxLength;
    private final StreamIdRule streamIdRule;

    FrameType(int code, int minLength, int maxLength, StreamIdRule streamIdRule) {
        this.code = code;
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.streamIdRule = streamIdRule;
    }

    public int code() {
        return code;
    }

    public int minLength() {
        return minLength;
    }

    public int maxLength() {
        return maxLength;
    }

    public StreamIdRule streamIdRule() {
        return streamIdRule;
    }

    /** The highest type code this enum recognises — anything above must be ignored per RFC 9113 §4.1. */
    public static int maxKnown() {
        return CONTINUATION.code;
    }

    /** Looks up the constant for a wire type byte, or {@code null} if it is an unrecognised (to-be-ignored) type. */
    public static FrameType fromCode(int code) {
        return (code >= 0 && code < BY_CODE.length) ? BY_CODE[code] : null;
    }
}
