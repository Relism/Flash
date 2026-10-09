package dev.relism.flash.http;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Http1Limits;
import dev.relism.flash.http.HttpStatus;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
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
 *
 * The connection driver (e.g. {@code Http1Connection}) owns one {@code Response} instance per
 * connection, reset before every handler call rather than reallocated — the same treatment {@link
 * Request} gets (see its Javadoc for the full pooling/dev-mode-guard rationale, which applies
 * identically here). <b>A handler that returns a different {@code Response} instance</b> (e.g.
 * {@code return new Response(404, "Not Found", ContentType.TEXT_PLAIN);}) is fully supported — that
 * instance is a normal, unpooled, freshly-constructed object like any public-constructor {@code
 * Response} always was; only the connection driver's own default instance is pooled and poisoned
 * after use.
 */
public class Response {
  private int statusCode;
  private byte[] statusBytes; // pre-encoded "200 OK"; null when set via status(int)
  private byte[] body;
  private InputStream stream;
  private long streamLength; // meaningful only when isStreaming() && !chunked
  private boolean chunked;
  private Streaming streamingBody;
  private boolean committed;
  private byte[] contentType;
  private final MutableHeaderMap trailers = new MutableHeaderMap();

  // Headers are kept as raw bytes in a reused region instead of rendered "Name: Value\r\n" lines,
  // so adding one allocates nothing once the region has grown to this connection's high-water mark.
  private ByteWriter headerRegion; // name+value bytes back to back
  private int[] headerQuads; // groups of (nameOff,nameLen,valOff,valLen), in call order
  private int headerQuadCount;

  private boolean active = true;

  private void checkActive() {
    if (!active) {
      throw new IllegalStateException(
          "Response used after the handler returned — do not retain a Response past the handler");
    }
  }

  // -------------------------------------------------------------------------
  // Constructors
  // -------------------------------------------------------------------------

  public Response(int statusCode, ContentType contentType) {
    this.statusCode = statusCode;
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
  // Pooling
  // -------------------------------------------------------------------------

  /**
   * Repositions this instance for a new request/response cycle — clears the body, stream, status,
   * content type, and every header recorded by the previous cycle. Public because the connection
   * driver that owns the pooled instance lives in a different package (matching {@link
   * RequestLine#reset}'s precedent); user code never calls this.
   */
  public Response reset(int statusCode, ContentType contentType) {
    this.statusCode = statusCode;
    this.statusBytes = null;
    this.body = null;
    this.stream = null;
    this.streamLength = 0;
    this.chunked = false;
    this.committed = false;
    this.streamingBody = null;
    this.contentType = contentType.getBytes();
    this.headerQuadCount = 0;
    if (headerRegion != null) headerRegion.reset();
    this.trailers.reset();
    this.active = true;
    return this;
  }

  /**
   * Marks this instance unsafe for further use — see {@link Request#recycle()} for the full
   * rationale, identical here. {@code public} for the same cross-package reason.
   */
  public void recycle() {
    this.active = false;
  }

  // -------------------------------------------------------------------------
  // Fluent mutators
  // -------------------------------------------------------------------------

  /** Sets the status code. The phrase is looked up from {@link HttpStatus} on the write path. */
  public Response status(int code) {
    checkHead();
    this.statusCode = code;
    this.statusBytes = null;
    return this;
  }

  /**
   * Lombok-style setter kept for API compatibility — equivalent to {@link #status(int)} without the
   * fluent return.
   */
  public void setStatusCode(int code) {
    status(code);
  }

  /**
   * Sets the status from an {@link HttpStatus} constant. The pre-encoded bytes are used directly on
   * the write path — zero lookup, zero allocation.
   */
  public Response status(HttpStatus status) {
    checkHead();
    this.statusCode = status.code();
    this.statusBytes = status.bytes();
    return this;
  }

  public Response type(ContentType ct) {
    checkHead();
    this.contentType = ct.getBytes();
    return this;
  }

  /** A content type encoded once, typically at boot: the array is kept, not copied, so it must not change. */
  public Response type(byte[] ct) {
    checkHead();
    this.contentType = ct;
    return this;
  }

  public Response type(String ct) {
    checkHead();
    this.contentType = ct.getBytes(StandardCharsets.UTF_8);
    return this;
  }

  public Response body(byte[] bytes) {
    checkActive();
    this.body = bytes;
    this.stream = null;
    this.streamingBody = null;
    return this;
  }

  public Response body(String text) {
    return body(text.getBytes(StandardCharsets.UTF_8));
  }

  /** Streaming response with known length; written with {@code Content-Length}. */
  public Response stream(InputStream is, long length) {
    checkActive();
    this.stream = is;
    this.streamLength = length;
    this.chunked = false;
    this.body = null;
    this.streamingBody = null;
    return this;
  }

  /** Streaming response with unknown length; written with {@code Transfer-Encoding: chunked}. */
  public Response chunked(InputStream is) {
    checkActive();
    this.stream = is;
    this.chunked = true;
    this.body = null;
    this.streamingBody = null;
    return this;
  }

  /**
   * Writes this response as it is produced: {@code body} runs once the handler and every
   * middleware around it have returned, so all of them can still set the status and headers. These
   * leave with the body's first write or flush, and the trailers when it returns. A body that never
   * writes answers like a handler that set no body; one that fails before writing answers with the
   * app's error handler.
   */
  public Response streaming(Streaming body) {
    checkActive();
    this.streamingBody = java.util.Objects.requireNonNull(body, "body");
    this.body = null;
    this.stream = null;
    return this;
  }

  @FunctionalInterface
  public interface Streaming {
    void write(OutputStream output) throws Exception;
  }

  Streaming streamingBody() { return streamingBody; }

  /** Marks the status and headers as sent. {@code public} for the connection packages. */
  public void commit() {
    committed = true;
  }

  /** Whether the status and headers are already on the wire. */
  public boolean isCommitted() {
    return committed;
  }

  private void checkHead() {
    checkActive();
    if (committed) throw new IllegalStateException("the response has started: set the status and headers before writing");
  }

  /** Adds a trailer rendered after the response body on both HTTP versions. */
  public Response trailer(String name, String value) {
    checkActive();
    validateTrailer(name, value);
    byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    trailers.add(nameBytes, 0, nameBytes.length, valueBytes, 0, valueBytes.length);
    return this;
  }

  /** Adds a pre-encoded structured trailer. */
  public Response trailer(PreEncodedHeader trailer) {
    checkActive();
    byte[] name = trailer.nameBytes();
    byte[] value = trailer.valueBytes();
    validateTrailer(
        new String(name, StandardCharsets.US_ASCII),
        new String(value, StandardCharsets.US_ASCII));
    trailers.add(name, 0, name.length, value, 0, value.length);
    return this;
  }

  private static void validateTrailer(String name, String value) {
    if (name.isEmpty() || name.charAt(0) == ':' || containsLineBreak(name)
        || containsLineBreak(value)) {
      throw new IllegalArgumentException("invalid response trailer");
    }
    if (name.equalsIgnoreCase("content-length")
        || name.equalsIgnoreCase("transfer-encoding")
        || name.equalsIgnoreCase("connection")
        || name.equalsIgnoreCase("host")
        || name.equalsIgnoreCase("te")
        || name.equalsIgnoreCase("trailer")) {
      throw new IllegalArgumentException("field is not permitted in response trailers: " + name);
    }
  }

  private static boolean containsLineBreak(String value) {
    return value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
  }

  /**
   * 302 Found redirect. Clears the body, sets status and {@code Location} header. Encoded once at
   * call time; zero-alloc on the write path.
   *
   * <pre>{@code
   * return res.redirect("/login");
   * }</pre>
   */
  public Response redirect(String url) {
    return redirect(HttpStatus.FOUND, url);
  }

  /**
   * Redirect with an explicit 3xx status. Use {@link HttpStatus#MOVED_PERMANENTLY}, {@link
   * HttpStatus#TEMPORARY_REDIRECT} (307), or {@link HttpStatus#PERMANENT_REDIRECT} (308) when
   * semantics matter.
   *
   * <pre>{@code
   * return res.redirect(HttpStatus.MOVED_PERMANENTLY, "/new-path");
   * }</pre>
   */
  public Response redirect(HttpStatus status, String url) {
    checkActive();
    this.statusCode = status.code();
    this.statusBytes = status.bytes();
    this.body = null;
    this.stream = null;
    this.streamingBody = null;
    return header("Location", url);
  }

  /**
   * Adds a header. Name and value are written as ASCII straight into the response's reused byte
   * region: no intermediate {@code String}, no re-encoding.
   */
  public Response header(String name, String value) {
    int nameOff = beginField();
    headerRegion.writeAscii(name);
    int valOff = headerRegion.length();
    headerRegion.writeAscii(value);
    return endField(nameOff, valOff);
  }

  /** Adds a header whose value is {@code value}'s decimal digits, written without a {@code String}; non-negative. */
  public Response header(String name, long value) {
    int nameOff = beginField();
    headerRegion.writeAscii(name);
    int valOff = headerRegion.length();
    headerRegion.writeDecimal(value);
    return endField(nameOff, valOff);
  }

  /** Adds a header built once, typically at boot: a copy of its bytes, not a re-encode. */
  public Response header(PreEncodedHeader preEncoded) {
    int nameOff = beginField();
    headerRegion.writeBytes(preEncoded.nameBytes());
    int valOff = headerRegion.length();
    headerRegion.writeBytes(preEncoded.valueBytes());
    return endField(nameOff, valOff);
  }

  private int beginField() {
    checkHead();
    checkHeaderBudget();
    if (headerRegion == null) {
      headerRegion = new ByteWriter(128);
      headerQuads = new int[16];
    }
    ensureQuadCapacity(headerQuadCount + 1);
    return headerRegion.length();
  }

  private Response endField(int nameOff, int valOff) {
    checkHeaderRegionBudget();
    int base = headerQuadCount * 4;
    headerQuads[base] = nameOff;
    headerQuads[base + 1] = valOff - nameOff;
    headerQuads[base + 2] = valOff;
    headerQuads[base + 3] = headerRegion.length() - valOff;
    headerQuadCount++;
    return this;
  }

  /** Prevents an unbounded header loop from growing the connection's response scratch state. */
  private void checkHeaderBudget() {
    if (headerQuadCount >= Http1Limits.MAX_RESPONSE_HEADER_COUNT) {
      throw new IllegalStateException(
          "response exceeds "
              + Http1Limits.MAX_RESPONSE_HEADER_COUNT
              + " headers — check for an unbounded loop calling header(...)");
    }
  }

  private void checkHeaderRegionBudget() {
    if (headerRegion.length() > Http1Limits.MAX_RESPONSE_HEADER_BYTES) {
      throw new IllegalStateException(
          "response header region exceeds "
              + Http1Limits.MAX_RESPONSE_HEADER_BYTES
              + " bytes — check for an unbounded loop or an oversized value passed to header(...)");
    }
  }

  private void ensureQuadCapacity(int neededQuads) {
    int neededInts = neededQuads * 4;
    if (neededInts <= headerQuads.length) return;
    int grown = headerQuads.length;
    while (grown < neededInts) grown *= 2;
    headerQuads = Arrays.copyOf(headerQuads, grown);
  }

  // -------------------------------------------------------------------------
  // State queries
  // -------------------------------------------------------------------------

  public boolean isStreaming() {
    checkActive();
    return stream != null;
  }

  public boolean isChunked() {
    checkActive();
    return chunked;
  }

  public int getStatusCode() {
    checkActive();
    return statusCode;
  }

  public byte[] getStatusBytes() {
    checkActive();
    return statusBytes;
  }

  public byte[] getBody() {
    checkActive();
    return body;
  }

  public byte[] getContentType() {
    checkActive();
    return contentType;
  }

  public InputStream getStream() {
    checkActive();
    return stream;
  }

  public long getStreamLength() {
    checkActive();
    return streamLength;
  }

  public boolean hasTrailers() {
    checkActive();
    return trailers.count() != 0;
  }

  public void writeTrailers(OutputStream output) throws IOException {
    checkActive();
    trailers.writeLines(output);
  }

  void forEachTrailerField(ResponseSerializer.FieldConsumer consumer) {
    trailers.forEachStructured(consumer);
  }

  // -------------------------------------------------------------------------
  // Internal setters used by the router for handler return values
  // -------------------------------------------------------------------------

  /**
   * Sets the body from a handler return value. Accepted types: {@code byte[]}, {@link String},
   * {@link CharSequence}. Any other non-null type throws {@link IllegalArgumentException} — return
   * a {@code Response} directly, or serialize to {@code String}/{@code byte[]} before returning.
   */
  public Response setBody(Object body) {
    checkActive();
    this.stream = null;
    this.streamingBody = null;
    if (body instanceof byte[] bytes) {
      this.body = bytes;
      return this;
    }
    if (body instanceof String s) {
      this.body = s.getBytes(StandardCharsets.UTF_8);
      return this;
    }
    if (body instanceof CharSequence s) {
      this.body = s.toString().getBytes(StandardCharsets.UTF_8);
      return this;
    }
    if (body != null)
      throw new IllegalArgumentException(
          "A handler returned a "
              + body.getClass().getName()
              + ": a body is a String, byte[], Response or null. Write an object through a codec,"
              + " json.write(res, value), or answer from a codec handler such as JsonHandler");
    return this;
  }

  /**
   * Returns custom headers as fully-rendered {@code "Name: Value\r\n"} lines, or an empty list if
   * none were added. Introspection/debugging accessor — reconstructs each line from the internal
   * region on every call, so it is not on the zero-alloc write path; {@link #writeHeaders} and
   * {@link ResponseSerializer} read the internal representation directly instead of going through
   * this method.
   */
  public List<byte[]> getHeaders() {
    checkActive();
    if (headerQuadCount == 0) return List.of();
    List<byte[]> result = new ArrayList<>(headerQuadCount);
    for (int i = 0; i < headerQuadCount; i++) {
      int base = i * 4;
      byte[] region = headerRegion.array();
      int nameOff = headerQuads[base], nameLen = headerQuads[base + 1];
      int valOff = headerQuads[base + 2], valLen = headerQuads[base + 3];
      byte[] line = new byte[nameLen + 2 + valLen + 2];
      int p = 0;
      System.arraycopy(region, nameOff, line, p, nameLen);
      p += nameLen;
      line[p++] = ':';
      line[p++] = ' ';
      System.arraycopy(region, valOff, line, p, valLen);
      p += valLen;
      line[p++] = '\r';
      line[p] = '\n';
      result.add(line);
    }
    return result;
  }

  /**
   * Writes every custom header directly into {@code head} (a scratch {@link ByteWriter} — This is
   * what {@code Http1ResponseWriter} uses; {@link #writeHeaders(OutputStream)} below (the {@code
   * OutputStream} equivalent) exists for the streaming-body write paths that cannot fold their
   * whole write into one scratch buffer.
   */
  public void writeHeadersInto(ByteWriter head) {
    for (int i = 0; i < headerQuadCount; i++) {
      int base = i * 4;
      byte[] region = headerRegion.array();
      head.writeBytes(region, headerQuads[base], headerQuads[base + 1]);
      head.writeByte((byte) ':');
      head.writeByte((byte) ' ');
      head.writeBytes(region, headerQuads[base + 2], headerQuads[base + 3]);
      head.writeByte((byte) '\r');
      head.writeByte((byte) '\n');
    }
  }

  /**
   * Writes every custom header directly to {@code out}, in call order. Zero-alloc when no headers
   * are set or on a warm region.
   */
  public void writeHeaders(OutputStream out) throws IOException {
    for (int i = 0; i < headerQuadCount; i++) {
      int base = i * 4;
      byte[] region = headerRegion.array();
      out.write(region, headerQuads[base], headerQuads[base + 1]);
      out.write(':');
      out.write(' ');
      out.write(region, headerQuads[base + 2], headerQuads[base + 3]);
      out.write('\r');
      out.write('\n');
    }
  }

  // ── Internal: name/value field enumeration for ResponseSerializer ──────────

  /**
   * Visits every added header as a (name, value) byte range. Package-private: {@link
   * ResponseSerializer} is this method's only caller.
   */
  void forEachStructuredField(ResponseSerializer.FieldConsumer consumer) {
    if (headerQuadCount == 0) return;
    byte[] region = headerRegion.array();
    for (int i = 0; i < headerQuadCount; i++) {
      int base = i * 4;
      consumer.accept(
          region,
          headerQuads[base],
          headerQuads[base + 1],
          region,
          headerQuads[base + 2],
          headerQuads[base + 3]);
    }
  }

  @Override
  public String toString() {
    return "Response(statusCode="
        + statusCode
        + ", contentType="
        + (contentType != null ? new String(contentType, StandardCharsets.UTF_8) : null)
        + ")";
  }
}
