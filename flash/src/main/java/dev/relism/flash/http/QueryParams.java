package dev.relism.flash.http;

import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.flash.bytes.Pairs;
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
        return decode(Pairs.hi(r), Pairs.lo(r));
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

    /** Returns {@code (valStart << 32) | valLen}, or {@code -1} if not found. */
    /** Where the first value of {@code name} sits in {@link #raw()}, packed by {@code Pairs}, or -1. */
    long range(String name) {
        return findFirst(name);
    }

    ByteView raw() {
        return raw;
    }

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
                if (keyMatches(keyStart, keyLen, name)) return Pairs.pack(valStart, i - valStart);
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
     *
     * {@code +} — scanned for first; when clean and {@link #raw} is array-backed, the
     * {@code String} is built directly from the backing array in one allocation, skipping the
     * scratch {@code byte[]} copy this method used to make unconditionally for every value.
     */
    private String decode(int start, int length) {
        boolean clean = true;
        for (int i = 0; i < length; i++) {
            byte b = raw.byteAt(start + i);
            if (b == '%' || b == '+') { clean = false; break; }
        }
        if (clean) {
            if (raw instanceof ArrayBackedByteView abv) {
                return new String(abv.array(), abv.offset() + start, length, StandardCharsets.UTF_8);
            }
            byte[] out = new byte[length];
            for (int i = 0; i < length; i++) out[i] = raw.byteAt(start + i);
            return new String(out, StandardCharsets.UTF_8);
        }

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
