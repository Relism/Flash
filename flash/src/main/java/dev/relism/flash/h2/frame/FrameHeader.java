package dev.relism.flash.h2.frame;

/**
 * A <b>flyweight</b> over one frame's 9-byte header plus its payload location, both still living
 * in {@link Http2FrameReader}'s own read buffer. One instance per connection, {@link #reset}
 * in place by every {@link Http2FrameReader#readFrame()} call — never allocated per frame
 * (mirrors the existing {@code WebSocketFrame} reuse idiom in {@code dev.relism.flash.websocket}).
 *
 * <h3>Lifetime contract</h3>
 * Valid only until the next {@link Http2FrameReader#readFrame()}/{@code consumeFrame()} call on
 * the same reader — same "do not retain past the handler" rule the rest of this codebase's
 * buffer-backed flyweights (`HeaderMap`, `WebSocketFrame`) already document. The payload bytes
 * are also transient: whatever layer needs to retain a DATA frame's payload past this window
 * must copy it out (R3 — the connection read buffer is shared, single-threaded, and reused).
 *
 * <h3>Reserved bit and unknown types</h3>
 * {@link #streamId()} has already had the wire's reserved high bit (RFC 9113 §4.1: "R: A
 * reserved 1-bit field... The semantics of this bit are undefined, and the bit MUST be ignored
 * when receiving") masked off during {@link #reset} — callers never see it and never need to
 * mask it themselves. {@link #type()} is {@code null} for a type code {@link FrameType} does not
 * recognise (i.e. {@code typeCode() > FrameType.maxKnown()}); per RFC 9113 §4.1 such frames must
 * be ignored, not rejected — {@link #typeCode()} remains available so the caller can still log
 * or count it before skipping the payload.
 */
public final class FrameHeader {
    private byte[] buf;
    private int length;
    private int typeCode;
    private FrameType type;
    private int flags;
    private int streamId;
    private int payloadOffset;

    /** Called by {@link Http2FrameReader} only, once the full 9-byte header is available at {@code buf[off]}. */
    void reset(byte[] buf, int off) {
        this.buf = buf;
        int b0 = buf[off] & 0xFF, b1 = buf[off + 1] & 0xFF, b2 = buf[off + 2] & 0xFF;
        this.length = (b0 << 16) | (b1 << 8) | b2;
        this.typeCode = buf[off + 3] & 0xFF;
        this.type = FrameType.fromCode(typeCode);
        this.flags = buf[off + 4] & 0xFF;
        // RFC 9113 §4.1: the top bit of byte 5 is reserved and MUST be ignored on receipt —
        // masked here, once, rather than requiring every caller to remember to.
        int b5 = buf[off + 5] & 0x7F;
        int b6 = buf[off + 6] & 0xFF, b7 = buf[off + 7] & 0xFF, b8 = buf[off + 8] & 0xFF;
        this.streamId = (b5 << 24) | (b6 << 16) | (b7 << 8) | b8;
        this.payloadOffset = off + 9;
    }

    /** Payload length in bytes, as declared by the frame header (0..2^24-1 before any limit check). */
    public int length() {
        return length;
    }

    /** The raw wire type byte, valid even when {@link #type()} is {@code null} (an unrecognised type). */
    public int typeCode() {
        return typeCode;
    }

    /** The recognised frame type, or {@code null} if {@link #typeCode()} is not one of RFC 9113's 10. */
    public FrameType type() {
        return type;
    }

    /** The raw flags byte — interpret via {@link FrameFlags}, which is type-specific. */
    public int flags() {
        return flags;
    }

    /** Stream identifier, reserved bit already masked. {@code 0} means "the connection itself". */
    public int streamId() {
        return streamId;
    }

    /** The backing buffer — see the class Javadoc's lifetime contract before retaining a reference. */
    public byte[] buffer() {
        return buf;
    }

    /** Offset of the first payload byte within {@link #buffer()}. Payload spans {@code [payloadOffset(), payloadOffset() + length())}. */
    public int payloadOffset() {
        return payloadOffset;
    }
}
