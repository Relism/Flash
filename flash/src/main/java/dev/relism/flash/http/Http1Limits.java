package dev.relism.flash.http;

/**
 * Bounds the HTTP/1.1 parser ({@code RequestParser}, {@code ChunkedInputStream}) enforces
 * against a peer's input, in one place.
 *
 * against a named constant here — never against an ad-hoc literal, and never by letting the
 * underlying buffer throw on overrun. Each field's Javadoc names the specific attack it bounds.
 *
 * Compare {@code dev.relism.flash.http2.Http2Limits}, the HTTP/2 equivalent.
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
     */
    public static final long MAX_CONTENT_LENGTH = 4L * 1024 * 1024 * 1024;

    /**
     * Maximum number of header lines accepted in a single request. Without this bound, a
     * request with tens of thousands of one-byte headers passes the total header-block size
     * check ({@code maxHeaderBufferSize}) while still forcing every subsequent
     * {@code Http1HeaderMap} lookup to scan all of them — turning a small request into quadratic CPU
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

    /**
     * buffer as the response head (status line + headers) and written with it in a single
     * {@code OutputStream.write} call; larger bodies are written in a second {@code write} right
     * after the head, since copying a large body into the head buffer first would cost more
     * (an extra full-body memcpy) than the syscall it saves. 8 KiB — matches this codebase's
     * other "one socket-buffer's worth" constants ({@code ConnectionScratch.RELAY_BUFFER_SIZE},
     * {@code BufferedByteSource.DEFAULT_BUFFER_SIZE}) rather than introducing an uncalibrated
     */
    public static final int INLINE_BODY_THRESHOLD = 8192;

    /**
     * {@code multipart/form-data} body. Without this bound, a peer can send an unbounded number
     * of minimal parts — each cheap individually but forcing unbounded growth of the parser's
     * {@code scanned} list and unbounded per-part header-parsing work, the multipart analogue of
     * {@link #MAX_CHUNKS_PER_BODY}.
     */
    public static final int MAX_MULTIPART_PARTS = 1_000;

    /**
     * {@code Content-Type}, …) accepted per multipart part. Real clients send at most two or
     * three; without a bound a peer could send an effectively unlimited number before the blank
     * line that ends a part's header block, forcing unbounded {@code HashMap} growth per part.
     */
    public static final int MAX_MULTIPART_PART_HEADER_COUNT = 20;

    /**
     * header block. {@code Multipart.readLine} otherwise has no bound of its own to fall back
     * on — unlike the top-level HTTP headers (bounded by {@link #MAX_HEADER_VALUE_LENGTH} in
     * {@code RequestParser}), a line here with no {@code \r\n} would grow its {@code StringBuilder}
     * without limit for as long as the peer keeps streaming bytes.
     */
    public static final int MAX_MULTIPART_HEADER_LINE_LENGTH = 8_192;

    /**
     * buffers eagerly into a {@code byte[]} — text fields (always buffered) and, during a full
     * {@code parts()}/{@code parts(String)} scan, file bodies too. {@link #MAX_CONTENT_LENGTH}
     * bounds the whole request body, but at 4 GiB (and effectively unbounded for a chunked body,
     * see {@link #MAX_CHUNKS_PER_BODY} × {@link #MAX_CHUNK_SIZE}) it does nothing to stop a
     * single part from exhausting the heap on its own — this is the bound that actually protects
     * {@code ByteArrayOutputStream}-style eager buffering. Deliberately does not apply to
     * {@code Part.materialize()} on a streaming file part returned by {@code Multipart.file()} —
     * that call is documented as an explicit, opt-in heap allocation the caller chooses to pay for.
     */
    public static final long MAX_MULTIPART_BUFFERED_PART_SIZE = 10L * 1024 * 1024;

    /**
     * Maximum combined size, in bytes, of every response header's name + value bytes
     * ({@code Response.header(...)}'s growable {@code headerRegion}). Unlike every other bound in
     * this class, this one guards against a bug in <em>Flash's own caller</em> rather than a
     * hostile peer — a handler that calls {@code header(...)} in an unbounded loop (e.g. echoing
     * an unbounded collection into headers) would otherwise grow this connection's scratch region
     * without limit for the rest of its lifetime, since it is never shrunk back down between
     */
    public static final int MAX_RESPONSE_HEADER_BYTES = 65_536;

    /**
     * Maximum number of {@code Response.header(...)} calls (any overload) accepted on a single
     * response. Same rationale as {@link #MAX_RESPONSE_HEADER_BYTES}: bounds the response-side
     * analogue of {@link #MAX_HEADER_COUNT}, since an unbounded call count grows the header index
     * arrays even if each individual header is small.
     */
    public static final int MAX_RESPONSE_HEADER_COUNT = 1_000;
}
