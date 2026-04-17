package dev.relism.models;

import dev.relism.http.ContentType;
import dev.relism.http.HttpStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * HTTP response. All mutating methods return {@code this} for fluent chaining.
 *
 * <pre>{@code
 * // fixed body
 * return new Response(200, "ok", ContentType.TEXT_PLAIN);
 *
 * // known-length stream → Content-Length header
 * return new Response(200, ContentType.BINARY).stream(Files.newInputStream(p), Files.size(p));
 *
 * // unknown-length stream → Transfer-Encoding: chunked
 * return new Response(200, ContentType.TEXT_PLAIN).chunked(source);
 * }</pre>
 */
@Getter
@ToString
public class Response {
    @Setter private int    statusCode;
    private byte[]         statusBytes;    // pre-encoded "200 OK"; null when set via status(int)
    private byte[]         body;
    @ToString.Exclude
    private InputStream    stream;
    private long           streamLength;   // meaningful only when isStreaming() && !chunked
    private boolean        chunked;
    private byte[]         contentType;
    @Getter(lombok.AccessLevel.NONE)
    private List<byte[]>   headers;        // pre-encoded "Name: Value\r\n" entries

    // -------------------------------------------------------------------------
    // Constructors
    // -------------------------------------------------------------------------

    public Response(int statusCode, ContentType contentType) {
        this.statusCode  = statusCode;
        this.contentType = contentType.getBytes();
    }

    public Response(int statusCode, byte[] body, ContentType contentType) {
        this(statusCode, contentType);
        this.body = body;
    }

    public Response(int statusCode, String text, ContentType contentType) {
        this(statusCode, text.getBytes(StandardCharsets.UTF_8), contentType);
    }

    // -------------------------------------------------------------------------
    // Fluent mutators
    // -------------------------------------------------------------------------

    /** Sets the status code. The phrase is looked up from {@link HttpStatus} on the write path. */
    public Response status(int code)        { this.statusCode = code; this.statusBytes = null; return this; }

    /** Sets the status from an {@link HttpStatus} constant. The pre-encoded bytes are used
     *  directly on the write path — zero lookup, zero allocation. */
    public Response status(HttpStatus status) { this.statusCode = status.code(); this.statusBytes = status.bytes(); return this; }
    public Response type(ContentType ct)    { this.contentType = ct.getBytes(); return this; }
    public Response type(String ct)         { this.contentType = ct.getBytes(StandardCharsets.UTF_8); return this; }

    public Response body(byte[] bytes) {
        this.body   = bytes;
        this.stream = null;
        return this;
    }

    public Response body(String text) {
        return body(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Streaming response with known length; written with {@code Content-Length}. */
    public Response stream(InputStream is, long length) {
        this.stream       = is;
        this.streamLength = length;
        this.chunked      = false;
        this.body         = null;
        return this;
    }

    /** Streaming response with unknown length; written with {@code Transfer-Encoding: chunked}. */
    public Response chunked(InputStream is) {
        this.stream  = is;
        this.chunked = true;
        this.body    = null;
        return this;
    }

    /**
     * 302 Found redirect. Clears the body, sets status and {@code Location} header.
     * Encoded once at call time; zero-alloc on the write path.
     *
     * <pre>{@code
     * return res.redirect("/login");
     * }</pre>
     */
    public Response redirect(String url) {
        return redirect(HttpStatus.FOUND, url);
    }

    /**
     * Redirect with an explicit 3xx status. Use {@link HttpStatus#MOVED_PERMANENTLY},
     * {@link HttpStatus#TEMPORARY_REDIRECT} (307), or {@link HttpStatus#PERMANENT_REDIRECT} (308)
     * when semantics matter.
     *
     * <pre>{@code
     * return res.redirect(HttpStatus.MOVED_PERMANENTLY, "/new-path");
     * }</pre>
     */
    public Response redirect(HttpStatus status, String url) {
        this.statusCode  = status.code();
        this.statusBytes = status.bytes();
        this.body        = null;
        this.stream      = null;
        if (headers == null) headers = new ArrayList<>();
        headers.add(("Location: " + url + "\r\n").getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /** Adds a response header. Encoded once at call time; zero-alloc on the write path. */
    public Response header(String name, String value) {
        if (headers == null) headers = new ArrayList<>();
        headers.add((name + ": " + value + "\r\n").getBytes(StandardCharsets.UTF_8));
        return this;
    }

    /**
     * Adds a pre-encoded header (e.g. a static {@code "X-RateLimit-Limit: 100\r\n"} byte array
     * pre-built at boot time). Zero-alloc on both the call path and the write path.
     */
    public Response header(byte[] preEncoded) {
        if (headers == null) headers = new ArrayList<>();
        headers.add(preEncoded);
        return this;
    }

    // -------------------------------------------------------------------------
    // State queries
    // -------------------------------------------------------------------------

    public boolean isStreaming()      { return stream != null; }

    // -------------------------------------------------------------------------
    // Internal setters used by HttpServer for handler return values
    // -------------------------------------------------------------------------

    /**
     * Sets the body from a handler return value. Accepted types: {@code byte[]},
     * {@link String}, {@link CharSequence}. Any other non-null type throws
     * {@link IllegalArgumentException} — return a {@code Response} directly, or
     * serialize to {@code String}/{@code byte[]} before returning.
     */
    public Response setBody(Object body) {
        if (body instanceof byte[] bytes)   { this.body = bytes; return this; }
        if (body instanceof String s)       { this.body = s.getBytes(StandardCharsets.UTF_8); return this; }
        if (body instanceof CharSequence s) { this.body = s.toString().getBytes(StandardCharsets.UTF_8); return this; }
        if (body != null) throw new IllegalArgumentException(
                "Handler returned unsupported type: " + body.getClass().getName()
                + " — return String, byte[], Response, or null");
        return this;
    }

    /** Returns custom headers, or an empty list if none were added. */
    public List<byte[]> getHeaders() { return headers != null ? headers : List.of(); }

    /** Writes pre-encoded custom headers directly to {@code out}. Zero-alloc when no headers are set. */
    public void writeHeaders(OutputStream out) throws IOException {
        if (headers == null) return;
        for (byte[] header : headers) out.write(header);
    }
}
