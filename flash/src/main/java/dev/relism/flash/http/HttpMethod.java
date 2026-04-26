package dev.relism.flash.http;

import lombok.Getter;
import java.nio.charset.StandardCharsets;

@Getter
public enum HttpMethod {
    GET, POST, PUT, DELETE, PATCH, OPTIONS, HEAD, TRACE, CONNECT, PURGE;

    private static final HttpMethod[] VALUES = values();
    private final byte[] bytes;

    HttpMethod() {
        this.bytes = this.name().getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Resolves the HttpMethod from a byte buffer segment using O(1) dispatch.
     * Extremely fast, zero allocations, branch-prediction friendly.
     */
    public static HttpMethod fromBytes(byte[] buf, int off, int len) {
        if (len == 0) return null;
        
        return switch (buf[off]) {
            case 'G' -> (len == 3 && buf[off + 1] == 'E' && buf[off + 2] == 'T') ? GET : null;
            case 'P' -> {
                if (len == 3 && buf[off + 1] == 'U' && buf[off + 2] == 'T') yield PUT;
                if (len == 4 && buf[off + 1] == 'O' && buf[off + 2] == 'S' && buf[off + 3] == 'T') yield POST;
                if (len == 5 && buf[off + 1] == 'A' && buf[off + 2] == 'T' && buf[off + 3] == 'C' && buf[off + 4] == 'H') yield PATCH;
                if (len == 5 && buf[off + 1] == 'U' && buf[off + 2] == 'R' && buf[off + 3] == 'G' && buf[off + 4] == 'E') yield PURGE;
                yield null;
            }
            case 'D' -> (len == 6 && buf[off + 1] == 'E' && buf[off + 2] == 'L' && buf[off + 3] == 'E' && buf[off + 4] == 'T' && buf[off + 5] == 'E') ? DELETE : null;
            case 'O' -> (len == 7 && buf[off + 1] == 'P' && buf[off + 2] == 'T' && buf[off + 3] == 'I' && buf[off + 4] == 'O' && buf[off + 5] == 'N' && buf[off + 6] == 'S') ? OPTIONS : null;
            case 'H' -> (len == 4 && buf[off + 1] == 'E' && buf[off + 2] == 'A' && buf[off + 3] == 'D') ? HEAD : null;
            case 'T' -> (len == 5 && buf[off + 1] == 'R' && buf[off + 2] == 'A' && buf[off + 3] == 'C' && buf[off + 4] == 'E') ? TRACE : null;
            case 'C' -> (len == 7 && buf[off + 1] == 'O' && buf[off + 2] == 'N' && buf[off + 3] == 'N' && buf[off + 4] == 'E' && buf[off + 5] == 'C' && buf[off + 6] == 'T') ? CONNECT : null;
            default -> null;
        };
    }

    //Only to be used for debugging/logging purposes, never in the hot path.
    @Override
    public String toString() {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
