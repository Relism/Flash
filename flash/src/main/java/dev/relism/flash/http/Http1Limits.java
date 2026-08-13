package dev.relism.flash.http;

/**
 * Bounds the HTTP/1.1 parser ({@code RequestParser}, {@code ChunkedInputStream}) enforces
 * against a peer's input, in one place.
 *
 * <p>Per R8, any code that reads a length, an index, a count, or a size off the wire checks it
 * against a named constant here — never against an ad-hoc literal, and never by letting the
 * underlying buffer throw on overrun. Each field's Javadoc names the specific attack it bounds.
 *
 * <p>Seeded in Phase 0 with the bounds required by {@code EX-03} (strict {@code Content-Length}
 * parsing) and {@code EX-08} (header count/size limits); extended in Phase 1 with the chunked-
 * transfer bounds ({@code EX-10}) and again in later phases as new h1 surfaces need a limit.
 * Compare {@code dev.relism.flash.h2.Http2Limits}, the HTTP/2 equivalent.
 */
public final class Http1Limits {

    private Http1Limits() {
    }

    /**
     * The largest {@code Content-Length} value accepted, in bytes. RFC 9112 places no upper
     * bound on the header's numeric value, but an unbounded value from a hostile peer is a
     * resource-exhaustion vector for any code path that pre-sizes a buffer from it. Requests
     * declaring a length above this are rejected with {@code 413 Payload Too Large} before any
     * body byte is read.
     *
     * <p>4 GiB — generous enough for legitimate large uploads (Flash is a general-purpose
     * server, not an API-only framework with a tiny default), while still bounding a hostile
     * peer to a finite, known-in-advance number rather than the effectively unbounded
     * {@code Long.MAX_VALUE} the parser accepted before this limit existed. Comfortably above
     * {@code Integer.MAX_VALUE} (~2.1 billion) so legitimate very-large declared lengths are
     * not confused with the int-overflow bug this same fix (EX-03) also closes.
     */
    public static final long MAX_CONTENT_LENGTH = 4L * 1024 * 1024 * 1024;

    /**
     * Maximum number of header lines accepted in a single request. Without this bound, a
     * request with tens of thousands of one-byte headers passes the total header-block size
     * check ({@code maxHeaderBufferSize}) while still forcing every subsequent
     * {@code HeaderMap} lookup to scan all of them — turning a small request into quadratic CPU
     * work per middleware that reads a header ({@code EX-08}, {@code EX-09}).
     */
    public static final int MAX_HEADER_COUNT = 100;

    /**
     * Maximum length, in bytes, of a single header field name. RFC 9110 §5.1 places no formal
     * limit; this bound exists purely to cap per-header memory and scan cost.
     */
    public static final int MAX_HEADER_NAME_LENGTH = 256;

    /**
     * Maximum length, in bytes, of a single header field value. Bounds per-header memory and
     * scan cost the same way {@link #MAX_HEADER_NAME_LENGTH} bounds the name.
     */
    public static final int MAX_HEADER_VALUE_LENGTH = 8_192;

    /**
     * Maximum length, in bytes, of the request line ({@code METHOD SP target SP version}).
     * Tracked separately from the overall header-buffer size so an oversized request line is
     * rejected with a specific, correct status ({@code 414 URI Too Long}) rather than folded
     * into the generic header-block-too-large case.
     */
    public static final int MAX_REQUEST_LINE_LENGTH = 8_192;

    /**
     * Maximum size, in bytes, of a single {@code Transfer-Encoding: chunked} chunk.
     * {@code ChunkedInputStream.readChunkSize} previously accepted any value up to 2 GiB before
     * rejecting it; a hostile peer can advertise a huge chunk size and then trickle bytes,
     * forcing the connection to stay open far longer than any legitimate chunk would need
     * (bounded separately by {@code bodyReadTimeoutMs}, but this limit catches the size claim
     * itself before that timeout would).
     */
    public static final long MAX_CHUNK_SIZE = 16L * 1024 * 1024;

    /**
     * Maximum length, in bytes, of the chunk-extension section (the optional
     * {@code ;name=value} data after a chunk size and before its CRLF, RFC 9112 §7.1.1). Flash
     * does not interpret chunk extensions; without a bound, a peer could send an arbitrarily
     * long extension on every chunk purely to waste CPU discarding it.
     */
    public static final int MAX_CHUNK_EXT_LENGTH = 256;

    /**
     * Maximum number of chunks accepted in a single request body. Without this bound, a peer
     * can send an unbounded number of minimal (or zero-length) chunks, each cheap individually
     * but collectively forcing unbounded per-chunk framing work — a "death by a thousand
     * chunks" variant of a slow-body attack.
     */
    public static final int MAX_CHUNKS_PER_BODY = 100_000;

    /**
     * Maximum number of trailer header lines accepted after the final chunk of a chunked body
     * (RFC 9112 §7.1.2). Bounded for the same reason {@link #MAX_HEADER_COUNT} bounds the
     * regular header section; trailer values are separately bounded by
     * {@link #MAX_HEADER_VALUE_LENGTH}.
     */
    public static final int MAX_TRAILER_COUNT = 50;
}
