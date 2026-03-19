package dev.relism.models;

import dev.relism.fpr.core.ByteView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lazy query parameter access ({@code ?key=value&...}). Backed by a zero-copy {@link ByteView}
 * over the raw query string bytes, no parsing at construction, values decoded on demand.
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
        int s = (int) (r >> 32), l = (int) (r & 0xFFFFFFFFL);
        byte[] bytes = new byte[l];
        for (int i = 0; i < l; i++) bytes[i] = raw.byteAt(s + i);
        return new String(bytes, StandardCharsets.UTF_8);
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
                    int valLen = i - valStart;
                    byte[] bytes = new byte[valLen];
                    for (int j = 0; j < valLen; j++) bytes[j] = raw.byteAt(valStart + j);
                    if (result == null) result = new ArrayList<>();
                    result.add(new String(bytes, StandardCharsets.UTF_8));
                }
            }
            if (i < len && raw.byteAt(i) == '&') i++;
        }
        return result != null ? result : List.of();
    }

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
}
