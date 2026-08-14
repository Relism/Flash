package dev.relism.flash.http2.frame;

/**
 * The frame-header flag bits (RFC 9113 §6), as bitwise constants plus predicate helpers.
 *
 * <h3>The deliberate collision</h3>
 * Bit {@code 0x1} means different things on different frame types: {@link #END_STREAM} on
 * {@code DATA}/{@code HEADERS}, {@link #ACK} on {@code SETTINGS}/{@code PING}. They are the same
 * bit position because the RFC defines flags per-type, not globally — reusing the numeric value
 * is intentional on the wire, not a naming accident here. **Never call {@link #isEndStream} on a
 * SETTINGS/PING frame's flags, or {@link #isAck} on a DATA/HEADERS frame's** — each predicate is
 * named for the one frame type family it is valid to call it on; mixing them up silently
 * misreads an unrelated bit rather than throwing, because the bit pattern is, by construction,
 * identical.
 *
 * <p>RFC 9113 §4.1: flag bits not defined for a frame's type MUST be ignored on receipt and MUST
 * NOT be set when sending. This class only ever tests bits it defines for the type the caller is
 * working with; undefined bits are never inspected.
 */
public final class FrameFlags {
    private FrameFlags() {}

    /** DATA/HEADERS: no more frames will be sent for this stream in this direction. */
    public static final int END_STREAM = 0x1;
    /** SETTINGS/PING: this frame acknowledges the peer's own frame, rather than proposing new values. */
    public static final int ACK = 0x1;
    /** HEADERS/PUSH_PROMISE/CONTINUATION: the header block is complete — no CONTINUATION follows. */
    public static final int END_HEADERS = 0x4;
    /** DATA/HEADERS/PUSH_PROMISE: a pad-length byte and trailing padding are present — see {@link Padding}. */
    public static final int PADDED = 0x8;
    /** HEADERS: deprecated stream-dependency/weight fields are present (RFC 9113 §5.3.2 — parsed and discarded). */
    public static final int PRIORITY = 0x20;

    public static boolean isEndStream(int flags) { return (flags & END_STREAM) != 0; }
    public static boolean isAck(int flags) { return (flags & ACK) != 0; }
    public static boolean isEndHeaders(int flags) { return (flags & END_HEADERS) != 0; }
    public static boolean isPadded(int flags) { return (flags & PADDED) != 0; }
    public static boolean hasPriority(int flags) { return (flags & PRIORITY) != 0; }
}
