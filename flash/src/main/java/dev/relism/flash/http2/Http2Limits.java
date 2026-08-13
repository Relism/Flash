package dev.relism.flash.http2;

/**
 * Every bound the HTTP/2 implementation enforces against a peer's input, in one place.
 *
 * Every wire-derived length, index, count, or size is checked against a named constant here —
 * never against an ad-hoc literal, and never by letting the
 * underlying array or buffer throw on overrun. Each field's Javadoc names the specific attack
 * or resource it bounds and, where one exists, the CVE.
 *
 * <p>These are compile-time defaults, not runtime configuration. A limit becomes configurable
 * only when the operational need and its safe range are established.
 *
 * <p>Each field is introduced with the feature that enforces it; this class contains no unused
 * placeholders.
 */
public final class Http2Limits {

    private Http2Limits() {
    }

    /**
     * Maximum number of streams a single connection may have open concurrently. Advertised to
     * the peer as {@code SETTINGS_MAX_CONCURRENT_STREAMS}. Bounds per-connection memory (each
     * open stream owns a per-stream HPACK arena and request/response state) against a peer that
     * simply opens streams and never closes them.
     */
    public static final int MAX_CONCURRENT_STREAMS = 100;

    /**
     * The largest frame payload we accept without the peer first raising it via our own
     * {@code SETTINGS_MAX_FRAME_SIZE}. RFC 9113 §4.2 fixes the protocol default at 16384 and
     * requires any advertised value to stay within {@code 16384..16777215}. Bounds the memory a
     * single frame read can force us to hold.
     */
    public static final int MAX_FRAME_SIZE_LOCAL = 16_384;

    /**
     * Maximum total size (name + value + 32 per RFC 7541 §4.1's accounting, summed over every
     * header) of a decoded header list. Advertised as {@code SETTINGS_MAX_HEADER_LIST_SIZE}
     * (RFC 9113 §6.5.2). This is the primary defence against an HPACK bomb: a small compressed
     * block that references dynamic-table entries to expand into an enormous header list.
     */
    public static final int MAX_HEADER_LIST_SIZE = 32_768;

    /**
     * Maximum number of CONTINUATION frames accepted for a single header block before the
     * connection is torn down. Defence against CVE-2024-27316 (the "HTTP/2 CONTINUATION
     * Flood"): a peer that never sets {@code END_HEADERS} can otherwise force unbounded
     * decode/reassembly work per header block.
     */
    public static final int MAX_CONTINUATION_FRAMES_PER_BLOCK = 8;

    /**
     * Maximum number of {@code RST_STREAM} frames accepted from the peer within
     * {@link #RESET_RATE_INTERVAL_MS}. Defence against CVE-2023-44487 ("HTTP/2 Rapid Reset"):
     * opening a stream and immediately resetting it does not count against
     * {@link #MAX_CONCURRENT_STREAMS}, so without a rate bound a peer can force unbounded
     * per-stream setup/teardown work at effectively unlimited concurrency.
     */
    public static final int MAX_RESET_STREAMS_PER_INTERVAL = 200;

    /** The rolling window (milliseconds) over which {@link #MAX_RESET_STREAMS_PER_INTERVAL} is measured. */
    public static final long RESET_RATE_INTERVAL_MS = 10_000;

    /**
     * Maximum number of new streams accepted from the peer within
     * {@link #RESET_RATE_INTERVAL_MS}. A companion bound to
     * {@link #MAX_RESET_STREAMS_PER_INTERVAL}: Rapid Reset defences that only count resets can
     * still be bypassed by a peer that creates streams fast enough that the reset counter never
     * saturates within any single window boundary.
     */
    public static final int MAX_STREAMS_CREATED_PER_INTERVAL = 400;

    /**
     * Maximum number of SETTINGS parameter entries accepted in a single SETTINGS frame. A
     * SETTINGS frame is already bounded in byte length by {@link #MAX_FRAME_SIZE_LOCAL} (each
     * entry is 6 bytes), but an explicit entry-count bound keeps the per-entry validation loop
     * itself cheap to reason about and gives a distinct, loud rejection reason.
     */
    public static final int MAX_SETTINGS_ENTRIES_PER_FRAME = 64;

    /**
     * Maximum number of outstanding (unanswered) PING responses queued for the writer. A PING
     * flood forces a PONG per PING; without a bound, a peer that reads its own responses slowly
     * can make us buffer unbounded PONG frames.
     */
    public static final int MAX_PING_QUEUE_DEPTH = 64;

    /**
     * Maximum number of zero-length DATA frames accepted per stream. Zero-length DATA consumes
     * no flow-control window, so window accounting does not bound it — without this limit a
     * peer can force unbounded per-frame dispatch/validation CPU work at zero cost to itself.
     */
    public static final int MAX_EMPTY_DATA_FRAMES_PER_STREAM = 1_000;

    /**
     * The value of {@code SETTINGS_INITIAL_WINDOW_SIZE} Flash advertises for every new stream:
     * deliberately large (1 MiB, versus the RFC default of 65535) so that a normal-sized
     */
    public static final int INITIAL_WINDOW_SIZE_LOCAL = 1_048_576;

    /**
     * The connection-level flow-control window Flash advertises. Sized above
     * {@link #INITIAL_WINDOW_SIZE_LOCAL} so a single active stream is never bottlenecked by the
     * connection window before its own stream window, but well below
     * {@code MAX_CONCURRENT_STREAMS * INITIAL_WINDOW_SIZE_LOCAL} — real traffic is never all
     * streams simultaneously saturating their windows, and sizing for that worst case would
     * commit 100 MiB of receive window to every connection regardless of load.
     */
    public static final int CONNECTION_WINDOW_SIZE_LOCAL = 16 * 1_048_576;

    /**
     * The HPACK dynamic table size Flash's decoder honours, in bytes of RFC 7541 §4.1
     * accounting. RFC 7541's protocol default. The encoder never uses a dynamic table at all
     */
    public static final int HPACK_DYNAMIC_TABLE_SIZE_LOCAL = 4_096;

    /**
     * Maximum length, in decoded bytes, of a single HPACK string literal. Applied during
     * Huffman decode as bytes are produced, not to the encoded length — a Huffman string can
     * expand by roughly 8/5, so bounding only the encoded length would let a compact input
     * still decode past this limit.
     */
    public static final int MAX_HPACK_STRING_LENGTH = 8_192;

    /**
     * Maximum time, in milliseconds, allowed between a HEADERS frame's arrival and the header
     * block's completion (its {@code END_HEADERS} flag, possibly after CONTINUATION frames). A
     * peer that starts a header block and then stalls indefinitely would otherwise hold the
     * per-stream arena and the connection's HPACK assembly buffer forever.
     */
    public static final long HEADER_BLOCK_ASSEMBLY_TIMEOUT_MS = 10_000;

    /**
     * Maximum time, in milliseconds, a stream may remain open with no frame activity in either
     * direction. Bounds resource pinning by a peer that opens a stream and then goes silent
     * without closing it — the h2 equivalent of the h1 slowloris defence in
     * {@code FlashConfiguration.idleKeepAliveTimeoutMs}.
     */
    public static final long STREAM_IDLE_TIMEOUT_MS = 60_000;

    /**
     * Maximum time, in milliseconds, {@code Http2FrameWriter} may spend blocked inside a single
     * socket write. A blocking write is unavoidable when the kernel send buffer is full and the
     * peer is not reading (that peer holds the connection's single writer lock for the duration
     * — see {@code WRITER.md}), but it must not be unbounded: a peer that simply stops reading
     * would otherwise let a single stalled connection wedge the writer forever. Enforced via a
     * background reaper interrupting the blocked thread past the deadline, not
     * {@code Socket#setSoTimeout} — that option bounds reads, not writes.
     */
    public static final long WRITE_TIMEOUT_MS = 30_000;

    /**
     * Maximum time, in milliseconds, {@code Http2FrameReader} may wait for a single frame's
     * header and payload to fully arrive. Bounds the same slowloris-shaped hazard {@code
     * without it, a peer that sends 9 header bytes and then never sends the declared payload
     * would hold this connection's frame reader waiting forever.
     */
    public static final long FRAME_READ_TIMEOUT_MS = 20_000;
}
