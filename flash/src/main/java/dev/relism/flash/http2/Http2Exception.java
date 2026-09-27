package dev.relism.flash.http2;

/**
 * A <b>connection-level</b> HTTP/2 error. Thrown anywhere a peer's frame, HPACK block, or
 * SETTINGS value violates the protocol in a way that leaves the connection's state (the HPACK
 * dynamic table, a flow-control window, the stream table) unrecoverable.
 *
 * The connection handles it at a
 * single site: it sends {@code GOAWAY} with {@link #errorCode()} and closes the connection.
 * Compare {@link Http2StreamException}, whose scope is one stream and which results in
 * {@code RST_STREAM} while the connection survives.
 *
 * <p>Deliberately does <b>not</b> extend {@link java.io.IOException}: the connection loop
 * distinguishes a protocol violation (a decision Flash made about the peer's bytes) from a
 * socket failure (the peer went away) by catching these as unrelated types. Conflating them
 * would make it possible to accidentally treat a hostile peer's malformed frame as a harmless
 * disconnect, or vice versa.
 *
 * <h3>Why stack traces are disabled</h3>
 * This exception is thrown on the connection's hot rejection path — a single malformed byte
 * from a hostile or buggy peer can trigger it, and under a scripted attack that can happen many
 * times per second across many connections. JVM stack trace capture ({@code fillInStackTrace})
 * is by far the most expensive part of constructing a {@code Throwable}, and it buys nothing
 * here: the call site is exactly where {@code errorCode()} says it is, and the debug message
 * already names the specific violation. The 4-argument {@link RuntimeException} constructor
 * disables both suppression and stack-trace writing.
 *
 * <h3>Preallocated singletons</h3>
 * For the common, message-less rejections (frame validation failures, HPACK structural errors)
 * this class exposes shared singleton instances. Reusing one instance across threads and across
 * many throws is safe <em>only because</em> the instance carries no per-throw mutable state and
 * writable-stack-trace is disabled — nothing about a throw mutates the exception object.
 */
public final class Http2Exception extends RuntimeException {

    private final Http2ErrorCode errorCode;

    private Http2Exception(Http2ErrorCode errorCode, String message) {
        super(message, null, false, false);
        this.errorCode = errorCode;
    }

    /** The RFC 9113 §7 error code to send in the {@code GOAWAY} frame. */
    public Http2ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * Builds a connection error carrying a caller-supplied debug message. Allocates a new
     * message carries information specific to this occurrence (e.g. the offending stream id or
     * a decoded value); use one of the preallocated singletons below when it does not.
     */
    public static Http2Exception of(Http2ErrorCode code, String message) {
        return new Http2Exception(code, message);
    }

    // ── Preallocated, message-less singletons for the hot rejection paths ──────────────────

    public static final Http2Exception PROTOCOL_ERROR =
            new Http2Exception(Http2ErrorCode.PROTOCOL_ERROR, "protocol error");
    public static final Http2Exception FRAME_SIZE_ERROR =
            new Http2Exception(Http2ErrorCode.FRAME_SIZE_ERROR, "frame size error");
    public static final Http2Exception FLOW_CONTROL_ERROR =
            new Http2Exception(Http2ErrorCode.FLOW_CONTROL_ERROR, "flow control error");
    public static final Http2Exception COMPRESSION_ERROR =
            new Http2Exception(Http2ErrorCode.COMPRESSION_ERROR, "compression error");
    public static final Http2Exception INTERNAL_ERROR =
            new Http2Exception(Http2ErrorCode.INTERNAL_ERROR, "internal error");
    public static final Http2Exception SETTINGS_TIMEOUT =
            new Http2Exception(Http2ErrorCode.SETTINGS_TIMEOUT, "settings ack timeout");
}
