package dev.relism.http;

import java.nio.charset.StandardCharsets;

/**
 * Pre-compiled byte representations of standard HTTP status lines.
 * Uses a direct-access array for O(1) lookup with zero allocation.
 */
public enum HttpStatus {

    // 1xx
    CONTINUE                        (100, "Continue"),
    SWITCHING_PROTOCOLS             (101, "Switching Protocols"),

    // 2xx
    OK                              (200, "OK"),
    CREATED                         (201, "Created"),
    ACCEPTED                        (202, "Accepted"),
    NO_CONTENT                      (204, "No Content"),
    PARTIAL_CONTENT                 (206, "Partial Content"),

    // 3xx
    MOVED_PERMANENTLY               (301, "Moved Permanently"),
    FOUND                           (302, "Found"),
    NOT_MODIFIED                    (304, "Not Modified"),
    TEMPORARY_REDIRECT              (307, "Temporary Redirect"),
    PERMANENT_REDIRECT              (308, "Permanent Redirect"),

    // 4xx
    BAD_REQUEST                     (400, "Bad Request"),
    UNAUTHORIZED                    (401, "Unauthorized"),
    FORBIDDEN                       (403, "Forbidden"),
    NOT_FOUND                       (404, "Not Found"),
    METHOD_NOT_ALLOWED              (405, "Method Not Allowed"),
    NOT_ACCEPTABLE                  (406, "Not Acceptable"),
    CONFLICT                        (409, "Conflict"),
    GONE                            (410, "Gone"),
    LENGTH_REQUIRED                 (411, "Length Required"),
    PAYLOAD_TOO_LARGE               (413, "Payload Too Large"),
    URI_TOO_LONG                    (414, "URI Too Long"),
    UNSUPPORTED_MEDIA_TYPE          (415, "Unsupported Media Type"),
    UNPROCESSABLE_ENTITY            (422, "Unprocessable Entity"),
    TOO_MANY_REQUESTS               (429, "Too Many Requests"),

    // 5xx
    INTERNAL_SERVER_ERROR           (500, "Internal Server Error"),
    NOT_IMPLEMENTED                 (501, "Not Implemented"),
    BAD_GATEWAY                     (502, "Bad Gateway"),
    SERVICE_UNAVAILABLE             (503, "Service Unavailable"),
    GATEWAY_TIMEOUT                 (504, "Gateway Timeout");

    private static final int MAX_STATUS_CODE = 504;
    private static final byte[][] INDEX = new byte[MAX_STATUS_CODE + 1][];
    private static final String[] REASONS = new String[MAX_STATUS_CODE + 1];

    static {
        for (HttpStatus s : values()) {
            INDEX[s.code] = s.bytes;
            REASONS[s.code] = s.reason;
        }
    }

    private final int    code;
    private final String reason;
    private final byte[] bytes;

    HttpStatus(int code, String reason) {
        this.code  = code;
        this.reason = reason;
        this.bytes = (code + " " + reason).getBytes(StandardCharsets.UTF_8);
    }

    /** Numeric status code (e.g. {@code 200}). */
    public int code() { return code; }

    /** Pre-encoded {@code "200 OK"} bytes — zero allocation on the write path. */
    public byte[] bytes() { return bytes; }

    /** Reason phrase (e.g. {@code "OK"}). */
    public String reason() { return reason; }

    /**
     * Returns pre-compiled status bytes for the given code.
     * Access is O(1) and generates zero garbage.
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
}
