package dev.relism.flash.models;

import dev.relism.fpr.core.ByteView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lazy query parameter access ({@code ?key=value&...}). Backed by a zero-copy {@link ByteView}
 * over the raw query string bytes, no parsing at construction, values percent-decoded on demand.
 *
 * <p>Percent-decoding ({@code %XX} → byte, {@code +} → space) follows the
 * {@code application/x-www-form-urlencoded} convention used by all browsers.
 */
public class QueryParams {
    public static final QueryParams EMPTY = new QueryParams(null);

    private final ByteView raw;

    public QueryParams(ByteView raw) {
        this.raw = raw;
    }

    public String get(String name) {
        long r = findFirst(name);
        if (r < 0) return null;
        return decode((int) (r >> 32), (int) (r & 0xFFFFFFFFL));
    }

    ByteView view(String name) {
        long r = findFirst(name);
        if (r < 0) return null;
        final int s = (int) (r >> 32), l = (int) (r & 0xFFFFFFFFL);
        return new ByteView() {
            public int  length()        { return l; }
            public byte byteAt(int idx) { return raw.byteAt(s + idx); }
        };
    }

    public List<String> getAll(String name) {
        if (raw == null) return List.of();
        List<String> result = null;
        int i = 0, len = raw.length();
        while (i < len) {
            int keyStart = i;
            while (i < len && raw.byteAt(i) != '=' && raw.byteAt(i) != '&') i++;
            int keyLen = i - keyStart;
            if (i < len && raw.byteAt(i) == '=') {
                i++;
                int valStart = i;
                while (i < len && raw.byteAt(i) != '&') i++;
                if (keyMatches(keyStart, keyLen, name)) {
                    if (result == null) result = new ArrayList<>();
                    result.add(decode(valStart, i - valStart));
                }
            }
            if (i < len && raw.byteAt(i) == '&') i++;
        }
        return result != null ? result : List.of();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Returns (valStart << 32) | valLen, or -1 if not found. */
    private long findFirst(String name) {
        if (raw == null) return -1L;
        int i = 0, len = raw.length();
        while (i < len) {
            int keyStart = i;
            while (i < len && raw.byteAt(i) != '=' && raw.byteAt(i) != '&') i++;
            int keyLen = i - keyStart;
            if (i < len && raw.byteAt(i) == '=') {
                i++;
                int valStart = i;
                while (i < len && raw.byteAt(i) != '&') i++;
                if (keyMatches(keyStart, keyLen, name)) return ((long) valStart << 32) | (i - valStart);
            }
            if (i < len && raw.byteAt(i) == '&') i++;
        }
        return -1L;
    }

    private boolean keyMatches(int start, int len, String name) {
        if (len != name.length()) return false;
        for (int i = 0; i < len; i++) if (raw.byteAt(start + i) != (byte) name.charAt(i)) return false;
        return true;
    }

    /**
     * Percent-decodes a value slice from {@code raw} into a UTF-8 String.
     * {@code %XX} triplets are decoded to their byte values; {@code +} decodes as space.
     * Invalid {@code %} sequences are passed through as-is.
     */
    private String decode(int start, int length) {
        byte[] out = new byte[length]; // upper bound — decoded is never longer
        int w = 0;
        for (int i = 0; i < length; i++) {
            byte b = raw.byteAt(start + i);
            if (b == '+') {
                out[w++] = ' ';
            } else if (b == '%' && i + 2 < length) {
                int hi = hexVal(raw.byteAt(start + i + 1));
                int lo = hexVal(raw.byteAt(start + i + 2));
                if (hi >= 0 && lo >= 0) {
                    out[w++] = (byte) ((hi << 4) | lo);
                    i += 2;
                } else {
                    out[w++] = b; // not a valid escape — keep literal %
                }
            } else {
                out[w++] = b;
            }
        }
        return new String(out, 0, w, StandardCharsets.UTF_8);
    }

    private static int hexVal(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return b - 'a' + 10;
        if (b >= 'A' && b <= 'F') return b - 'A' + 10;
        return -1;
    }
}
