package dev.relism.flash.http;

/**
 * The bounds the HTTP/1.1 parser ({@code RequestParser}, {@code ChunkedInputStream},
 * {@code Multipart}) enforces against a peer's input, in one place. Every check compares against a
 * constant here, never against a literal and never by letting a buffer overrun. The body as a
 * whole is bounded per server by {@code ServerConfig.maxBodySize}.
 *
 * <p>Compare {@code dev.relism.flash.http2.Http2Limits}, the HTTP/2 equivalent.
 */
public final class Http1Limits {

    private Http1Limits() {
    }

    /**
     * Header lines per request. Without it, tens of thousands of one-byte headers fit the header
     * buffer and make every header lookup scan all of them.
     */
    public static final int MAX_HEADER_COUNT = 100;

    /** Bytes in one header name. RFC 9110 §5.1 sets none; this caps per-header memory and scan cost. */
    public static final int MAX_HEADER_NAME_LENGTH = 256;

    /** Bytes in one header value, for the same reason as {@link #MAX_HEADER_NAME_LENGTH}. */
    public static final int MAX_HEADER_VALUE_LENGTH = 8_192;

    /**
     * Bytes in the request line ({@code METHOD SP target SP version}), checked apart from the
     * header buffer so an oversized line gets its own status (414) instead of the generic one.
     */
    public static final int MAX_REQUEST_LINE_LENGTH = 8_192;

    /**
     * Bytes in one chunk of a chunked body. Catches a huge advertised chunk before the body-read
     * timeout would.
     */
    public static final long MAX_CHUNK_SIZE = 16L * 1024 * 1024;

    /**
     * Bytes of chunk extensions ({@code ;name=value} after a chunk size, RFC 9112 §7.1.1). Flash
     * ignores them, and a long one would only waste CPU being discarded.
     */
    public static final int MAX_CHUNK_EXT_LENGTH = 256;

    /** Chunks per body: many empty chunks are a slow-body attack of their own. */
    public static final int MAX_CHUNKS_PER_BODY = 100_000;

    /**
     * Trailer lines after the last chunk (RFC 9112 §7.1.2), for the same reason as
     * {@link #MAX_HEADER_COUNT}; their values are bounded by {@link #MAX_HEADER_VALUE_LENGTH}.
     */
    public static final int MAX_TRAILER_COUNT = 50;

    /**
     * A body up to this size is copied into the response head's buffer and leaves in one write;
     * a larger one follows the head in a second write, which is cheaper than copying it.
     */
    public static final int INLINE_BODY_THRESHOLD = 8192;

    /** Parts in one {@code multipart/form-data} body, the multipart analogue of {@link #MAX_CHUNKS_PER_BODY}. */
    public static final int MAX_MULTIPART_PARTS = 1_000;

    /** Header lines per multipart part; real clients send two or three. */
    public static final int MAX_MULTIPART_PART_HEADER_COUNT = 20;

    /** Bytes in one line of a part's header block, which has no {@code \r\n} bound of its own otherwise. */
    public static final int MAX_MULTIPART_HEADER_LINE_LENGTH = 8_192;

    /**
     * Bytes of one part the parser buffers itself: text fields always, file bodies during a full
     * {@code parts()} scan. {@code Part.materialize()} on a streamed file part is the caller's own,
     * explicit allocation and is not bounded here.
     */
    public static final long MAX_MULTIPART_BUFFERED_PART_SIZE = 10L * 1024 * 1024;

    /**
     * Bytes of response header names and values. Guards against a handler that adds headers in an
     * unbounded loop, which would otherwise grow the connection's header region for good.
     */
    public static final int MAX_RESPONSE_HEADER_BYTES = 65_536;

    /** {@code Response.header(...)} calls per response, for the same reason. */
    public static final int MAX_RESPONSE_HEADER_COUNT = 1_000;
}
