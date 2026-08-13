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
     */
    public static final long MAX_CONTENT_LENGTH = 100L * 1024 * 1024;

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
}
