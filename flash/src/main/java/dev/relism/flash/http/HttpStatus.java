package dev.relism.flash.http;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Pre-compiled byte representations of standard HTTP status lines. Uses a direct-access array for
 * O(1) lookup with zero allocation.
 */
public enum HttpStatus {

  // 1xx
  CONTINUE(100, "Continue"),
  SWITCHING_PROTOCOLS(101, "Switching Protocols"),

  // 2xx
  OK(200, "OK"),
  CREATED(201, "Created"),
  ACCEPTED(202, "Accepted"),
  NO_CONTENT(204, "No Content"),
  PARTIAL_CONTENT(206, "Partial Content"),
  MULTI_STATUS(207, "Multi-Status"),

  // 3xx
  MOVED_PERMANENTLY(301, "Moved Permanently"),
  FOUND(302, "Found"),
  NOT_MODIFIED(304, "Not Modified"),
  TEMPORARY_REDIRECT(307, "Temporary Redirect"),
  PERMANENT_REDIRECT(308, "Permanent Redirect"),

  // 4xx
  BAD_REQUEST(400, "Bad Request"),
  UNAUTHORIZED(401, "Unauthorized"),
  FORBIDDEN(403, "Forbidden"),
  NOT_FOUND(404, "Not Found"),
  METHOD_NOT_ALLOWED(405, "Method Not Allowed"),
  NOT_ACCEPTABLE(406, "Not Acceptable"),
  CONFLICT(409, "Conflict"),
  GONE(410, "Gone"),
  LENGTH_REQUIRED(411, "Length Required"),
  PRECONDITION_FAILED(412, "Precondition Failed"),
  PAYLOAD_TOO_LARGE(413, "Payload Too Large"),
  URI_TOO_LONG(414, "URI Too Long"),
  UNSUPPORTED_MEDIA_TYPE(415, "Unsupported Media Type"),
  RANGE_NOT_SATISFIABLE(416, "Range Not Satisfiable"),
  EXPECTATION_FAILED(417, "Expectation Failed"),
  MISDIRECTED_REQUEST(421, "Misdirected Request"),
  UNPROCESSABLE_ENTITY(422, "Unprocessable Entity"),
  LOCKED(423, "Locked"),
  FAILED_DEPENDENCY(424, "Failed Dependency"),
  TOO_MANY_REQUESTS(429, "Too Many Requests"),
  REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),

  // 5xx
  INTERNAL_SERVER_ERROR(500, "Internal Server Error"),
  NOT_IMPLEMENTED(501, "Not Implemented"),
  BAD_GATEWAY(502, "Bad Gateway"),
  SERVICE_UNAVAILABLE(503, "Service Unavailable"),
  GATEWAY_TIMEOUT(504, "Gateway Timeout"),
  HTTP_VERSION_NOT_SUPPORTED(505, "HTTP Version Not Supported"),
  INSUFFICIENT_STORAGE(507, "Insufficient Storage"),
  NETWORK_AUTHENTICATION_REQUIRED(511, "Network Authentication Required");

  // ArrayIndexOutOfBoundsException from this static initializer the moment any constant
  // above it (421, 431, 505, 507, 511 — several of which HTTP/2 needs, see MISDIRECTED_REQUEST
  // and REQUEST_HEADER_FIELDS_TOO_LARGE above) was added. Computed from values() instead, so
  // adding a status code can never silently break class loading again.
  private static final int MAX_STATUS_CODE;
  private static final byte[][] INDEX;
  private static final byte[][] HPACK_INDEX;
  private static final String[] REASONS;

  static {
    int max = 0;
    for (HttpStatus s : values()) max = Math.max(max, s.code);
    MAX_STATUS_CODE = max;
    INDEX = new byte[MAX_STATUS_CODE + 1][];
    HPACK_INDEX = new byte[MAX_STATUS_CODE + 1][];
    REASONS = new String[MAX_STATUS_CODE + 1];
    for (HttpStatus s : values()) {
      INDEX[s.code] = s.bytes;
      HPACK_INDEX[s.code] = s.hpackBytes;
      REASONS[s.code] = s.reason;
    }
  }

  private final int code;
  private final String reason;
  private final byte[] bytes;
  private final byte[] hpackBytes;

  HttpStatus(int code, String reason) {
    this.code = code;
    this.reason = reason;
    this.bytes = (code + " " + reason).getBytes(StandardCharsets.UTF_8);
    this.hpackBytes = encodeHpack(code);
  }

  /** Numeric status code (e.g. {@code 200}). */
  public int code() {
    return code;
  }

  /** Pre-encoded {@code "200 OK"} bytes — zero allocation on the write path. */
  public byte[] bytes() {
    return bytes;
  }

  /** Precompiled HPACK representation of {@code :status}. */
  public byte[] hpackBytes() {
    return hpackBytes;
  }

  /** Reason phrase (e.g. {@code "OK"}). */
  public String reason() {
    return reason;
  }

  /**
   * Returns pre-compiled status bytes for the given code. Access is O(1) and generates zero
   * garbage.
   */
  public static byte[] bytesForCode(int code) {
    if (code >= 0 && code <= MAX_STATUS_CODE) {
      return INDEX[code];
    }
    return null;
  }

  /** Returns reason phrase for the given code, or null if unknown. */
  public static String reasonForCode(int code) {
    if (code >= 0 && code <= MAX_STATUS_CODE) {
      return REASONS[code];
    }
    return null;
  }

  /** Returns the precompiled HPACK status field for a known code, or {@code null}. */
  public static byte[] hpackBytesForCode(int code) {
    return code >= 0 && code <= MAX_STATUS_CODE ? HPACK_INDEX[code] : null;
  }

  private static byte[] encodeHpack(int code) {
    int staticIndex =
        switch (code) {
          case 200 -> 8;
          case 204 -> 9;
          case 206 -> 10;
          case 304 -> 11;
          case 400 -> 12;
          case 404 -> 13;
          case 500 -> 14;
          default -> 0;
        };
    ByteWriter out = new ByteWriter(8);
    if (staticIndex != 0) {
      HpackEncoder.writeIndexed(out, staticIndex);
    } else {
      byte[] value = {
        (byte) ('0' + code / 100), (byte) ('0' + code / 10 % 10), (byte) ('0' + code % 10)
      };
      HpackEncoder.writeLiteralWithNameIndex(out, 8, value, true);
    }
    return java.util.Arrays.copyOf(out.array(), out.length());
  }
}
