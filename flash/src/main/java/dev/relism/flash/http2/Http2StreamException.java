package dev.relism.flash.http2;

/**
 * A <b>stream-level</b> HTTP/2 error, scoped to one stream id. Results in an {@code RST_STREAM}
 * frame for {@link #streamId()} with {@link #errorCode()}; the connection and every other
 * stream on it are unaffected. Compare {@link Http2Exception}, whose scope is the whole
 * connection.
 *
 * <p>Deliberately does <b>not</b> extend {@link java.io.IOException}, for the same reason as
 * {@link Http2Exception}: the connection loop must be able to distinguish "we decided to reject
 * this stream" from "the socket failed" by catching unrelated exception types.
 *
 * <h3>Why this allocates, unlike {@code Http2Exception}'s singletons</h3>
 * Every instance carries a distinct {@link #streamId()}, so it cannot be a shared singleton the
 * exempts error paths. The scenario where this matters most — a peer opening and resetting
 * thousands of streams per second (the Rapid Reset pattern, CVE-2023-44487) — is bounded by
 * that can force RST_STREAM generation fast enough for GC pressure to matter has already
 * tripped {@code Http2Limits.MAX_RESET_STREAMS_PER_INTERVAL} and the connection is being torn
 * down anyway.
 *
 * <p>Stack trace capture is disabled for the same cost reason as {@link Http2Exception}.
 */
public final class Http2StreamException extends RuntimeException {

    private final Http2ErrorCode errorCode;
    private final int streamId;

    public Http2StreamException(int streamId, Http2ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.streamId = streamId;
        this.errorCode = errorCode;
    }

    /** The id of the stream this error terminates. */
    public int streamId() {
        return streamId;
    }

    /** The RFC 9113 §7 error code to send in the {@code RST_STREAM} frame. */
    public Http2ErrorCode errorCode() {
        return errorCode;
    }
}
