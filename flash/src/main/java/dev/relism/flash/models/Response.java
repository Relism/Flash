package dev.relism.flash.models;

import dev.relism.flash.Flash;
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
import java.util.Objects;
import java.util.function.Consumer;

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
  private boolean pushStreaming;
  private byte[] contentType;
  private final MutableHeaderMap trailers = new MutableHeaderMap();

  // of a List<byte[]> of fully-rendered "Name: Value\r\n" lines (which cost a StringBuilder +
  // char[] + String + getBytes() chain per header(String,String) call). Two backing stores,
  // unified into one insertion-ordered sequence via headerTags/headerRefs, since a fully
  // pre-rendered line (the legacy header(byte[]) overload) has no name/value structure to
  // decompose into the same region:
  //   tag 0 -> a (name, value) pair; headerRefs[i] indexes headerQuads (groups of 4)
  //   tag 1 -> a raw pre-rendered line; headerRefs[i] indexes rawHeaderLines
  private ByteWriter headerRegion; // tag-0 storage: name+value bytes back to back
  private int[] headerQuads; // tag-0 storage: groups of (nameOff,nameLen,valOff,valLen)
  private int headerQuadCount;
  private List<byte[]> rawHeaderLines; // tag-1 storage: legacy header(byte[]) entries, verbatim
  private byte[] headerTags; // one entry per header(), in call order: 0 or 1
  private int[] headerRefs; // one entry per header(), in call order: index into the tag's store
  private int headerCount; // total header() calls this response has recorded

  private boolean active = true;
  private static volatile boolean poisoningEnabled = Flash.DEV;

  /**
   * Test-only override of the dev-mode poisoning check — mirrors {@code Request}'s identical hook.
   */
  static void setPoisoningEnabledForTesting(boolean enabled) {
    poisoningEnabled = enabled;
  }

  private void checkActive() {
    if (poisoningEnabled && !active) {
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
    this.pushStreaming = false;
    this.contentType = contentType.getBytes();
    this.headerQuadCount = 0;
    this.headerCount = 0;
    this.trailers.reset();
    if (rawHeaderLines != null) rawHeaderLines.clear();
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
    checkActive();
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
    checkActive();
    this.statusCode = status.code();
    this.statusBytes = status.bytes();
    return this;
  }

  public Response type(ContentType ct) {
    checkActive();
    this.contentType = ct.getBytes();
    return this;
  }

  public Response type(String ct) {
    checkActive();
    this.contentType = ct.getBytes(StandardCharsets.UTF_8);
    return this;
  }

  public Response body(byte[] bytes) {
    checkActive();
    this.body = bytes;
    this.stream = null;
    this.pushStreaming = false;
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
    this.pushStreaming = false;
    return this;
  }

  /** Streaming response with unknown length; written with {@code Transfer-Encoding: chunked}. */
  public Response chunked(InputStream is) {
    checkActive();
    this.stream = is;
    this.chunked = true;
    this.body = null;
    this.pushStreaming = false;
    return this;
  }

  /** Push-style streaming response with bounded blocking backpressure. */
  public Response streaming(Consumer<ResponseStream> producer) {
    checkActive();
    this.stream = new ProducerInputStream(Objects.requireNonNull(producer), this);
    this.streamLength = -1;
    this.chunked = true;
    this.pushStreaming = true;
    this.body = null;
    return this;
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
    return header("Location", url);
  }

  /**
   * reused byte region (via {@link ByteWriter#writeAscii}) instead of building an intermediate
   * {@code String} and re-encoding it — zero allocation once the region has grown to this
   * connection's high-water mark.
   */
  public Response header(String name, String value) {
    checkActive();
    checkHeaderBudget();
    if (headerRegion == null) {
      headerRegion = new ByteWriter(128);
      headerQuads = new int[16];
    }
    ensureQuadCapacity(headerQuadCount + 1);
    int nameOff = headerRegion.length();
    headerRegion.writeAscii(name);
    int nameLen = headerRegion.length() - nameOff;
    int valOff = headerRegion.length();
    headerRegion.writeAscii(value);
    int valLen = headerRegion.length() - valOff;
    checkHeaderRegionBudget();

    int base = headerQuadCount * 4;
    headerQuads[base] = nameOff;
    headerQuads[base + 1] = nameLen;
    headerQuads[base + 2] = valOff;
    headerQuads[base + 3] = valLen;
    recordHeaderEntry((byte) 0, headerQuadCount);
    headerQuadCount++;
    return this;
  }

  /**
   * Adds a header from a {@link PreEncodedHeader} built once (typically at boot). Copies its
   * precomputed {@code name}/{@code value} bytes into this response's region — a memcpy, not a
   * re-encode. Preserving the field structure makes it usable by both HTTP versions.
   */
  public Response header(PreEncodedHeader preEncoded) {
    checkActive();
    checkHeaderBudget();
    if (headerRegion == null) {
      headerRegion = new ByteWriter(128);
      headerQuads = new int[16];
    }
    ensureQuadCapacity(headerQuadCount + 1);
    byte[] nameBytes = preEncoded.nameBytes();
    byte[] valueBytes = preEncoded.valueBytes();
    int nameOff = headerRegion.length();
    headerRegion.writeBytes(nameBytes);
    int valOff = headerRegion.length();
    headerRegion.writeBytes(valueBytes);
    checkHeaderRegionBudget();

    int base = headerQuadCount * 4;
    headerQuads[base] = nameOff;
    headerQuads[base + 1] = nameBytes.length;
    headerQuads[base + 2] = valOff;
    headerQuads[base + 3] = valueBytes.length;
    recordHeaderEntry((byte) 0, headerQuadCount);
    headerQuadCount++;
    return this;
  }

  /**
   * Adds a pre-encoded, fully-rendered header line (e.g. a static {@code "X-RateLimit-Limit:
   * 100\r\n"} byte array pre-built at boot time). Zero-alloc on both the call path and the h1 write
   * path.
   *
   * <p><b>h1-only</b>: a rendered {@code "Name: Value\r\n"} line carries no structured name/value
   * data an HPACK encoder could use, so this header is not representable on a HTTP/2 response path
   * — prefer {@link #header(PreEncodedHeader)} for anything that must render correctly on both
   * protocols. Kept for existing HTTP/1-only callers.
   */
  public Response header(byte[] preEncoded) {
    checkActive();
    checkHeaderBudget();
    if (rawHeaderLines == null) rawHeaderLines = new ArrayList<>();
    rawHeaderLines.add(preEncoded);
    recordHeaderEntry((byte) 1, rawHeaderLines.size() - 1);
    return this;
  }

  /** Prevents an unbounded header loop from growing the connection's response scratch state. */
  private void checkHeaderBudget() {
    if (headerCount >= Http1Limits.MAX_RESPONSE_HEADER_COUNT) {
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

  private void recordHeaderEntry(byte tag, int ref) {
    if (headerTags == null) {
      headerTags = new byte[16];
      headerRefs = new int[16];
    } else if (headerCount == headerTags.length) {
      int grown = headerTags.length * 2;
      headerTags = Arrays.copyOf(headerTags, grown);
      headerRefs = Arrays.copyOf(headerRefs, grown);
    }
    headerTags[headerCount] = tag;
    headerRefs[headerCount] = ref;
    headerCount++;
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

  /** Internal distinction between producer-driven and InputStream-driven response bodies. */
  public boolean isPushStreaming() {
    checkActive();
    return pushStreaming;
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
  // Internal setters used by HttpServer for handler return values
  // -------------------------------------------------------------------------

  /**
   * Sets the body from a handler return value. Accepted types: {@code byte[]}, {@link String},
   * {@link CharSequence}. Any other non-null type throws {@link IllegalArgumentException} — return
   * a {@code Response} directly, or serialize to {@code String}/{@code byte[]} before returning.
   */
  public Response setBody(Object body) {
    checkActive();
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
          "Handler returned unsupported type: "
              + body.getClass().getName()
              + " — return String, byte[], Response, or null");
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
    if (headerCount == 0) return List.of();
    List<byte[]> result = new ArrayList<>(headerCount);
    for (int i = 0; i < headerCount; i++) {
      if (headerTags[i] == 1) {
        result.add(rawHeaderLines.get(headerRefs[i]));
      } else {
        int base = headerRefs[i] * 4;
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
    for (int i = 0; i < headerCount; i++) {
      if (headerTags[i] == 1) {
        head.writeBytes(rawHeaderLines.get(headerRefs[i]));
      } else {
        int base = headerRefs[i] * 4;
        byte[] region = headerRegion.array();
        head.writeBytes(region, headerQuads[base], headerQuads[base + 1]);
        head.writeByte((byte) ':');
        head.writeByte((byte) ' ');
        head.writeBytes(region, headerQuads[base + 2], headerQuads[base + 3]);
        head.writeByte((byte) '\r');
        head.writeByte((byte) '\n');
      }
    }
  }

  /**
   * Writes every custom header directly to {@code out}, in call order. Zero-alloc when no headers
   * are set or on a warm region.
   */
  public void writeHeaders(OutputStream out) throws IOException {
    for (int i = 0; i < headerCount; i++) {
      if (headerTags[i] == 1) {
        out.write(rawHeaderLines.get(headerRefs[i]));
      } else {
        int base = headerRefs[i] * 4;
        byte[] region = headerRegion.array();
        out.write(region, headerQuads[base], headerQuads[base + 1]);
        out.write(':');
        out.write(' ');
        out.write(region, headerQuads[base + 2], headerQuads[base + 3]);
        out.write('\r');
        out.write('\n');
      }
    }
  }

  // ── Internal: name/value field enumeration for ResponseSerializer ──────────

  /**
   * Visits every {@code header(String,String)}/{@code header(PreEncodedHeader)}-added field as a
   * structured (name, value) byte range — <b>not</b> the {@code header(byte[])} legacy entries,
   * which have no such structure (see that method's own Javadoc). Package-private: {@link
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
