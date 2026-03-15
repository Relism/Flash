package dev.relism.models;

import dev.relism.fpr.core.ByteView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Zero-allocation header storage backed by raw byte offsets into the shared request buffer.
 * At parse time, only int offsets are recorded — zero objects allocated per header.
 * String conversion is lazy: happens only when getFirst() or getAll() are called.
 */
public class HeaderMap {
    private static final int MAX_HEADERS = 32;

    private final byte[] buffer;
    private final int[] keys   = new int[MAX_HEADERS * 2]; // interleaved [start, len] pairs
    private final int[] values = new int[MAX_HEADERS * 2]; // interleaved [start, len] pairs
    private int count = 0;

    public HeaderMap(byte[] buffer) {
        this.buffer = buffer;
    }

    /** Called at parse time. Stores raw offsets — zero allocations. */
    public void add(int keyStart, int keyLen, int valStart, int valLen) {
        if (count >= MAX_HEADERS) throw new IllegalStateException("Too many headers: limit is " + MAX_HEADERS);
        keys[count * 2]     = keyStart;
        keys[count * 2 + 1] = keyLen;
        values[count * 2]     = valStart;
        values[count * 2 + 1] = valLen;
        count++;
    }

    /** Lazy: decodes and returns the first matching value as a String. */
    public String getFirst(String name) {
        int idx = indexOf(name);
        if (idx < 0) return null;
        return new String(buffer, values[idx * 2], values[idx * 2 + 1], StandardCharsets.UTF_8);
    }

    /** Lazy: decodes and returns all matching values as a List<String>. */
    public List<String> getAll(String name) {
        List<String> result = null;
        for (int i = 0; i < count; i++) {
            if (keyMatches(i, name)) {
                if (result == null) result = new ArrayList<>();
                result.add(new String(buffer, values[i * 2], values[i * 2 + 1], StandardCharsets.UTF_8));
            }
        }
        return result != null ? result : List.of();
    }

    /** Lazy: decodes and returns all values as a List<String>. */
    public List<String> getAll() {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            result.add(new String(buffer, values[i * 2], values[i * 2 + 1], StandardCharsets.UTF_8));
        }
        return result;
    }

    /** Zero-copy: returns a ByteView over the raw value bytes without allocating a String. */
    public ByteView getView(String name) {
        int idx = indexOf(name);
        if (idx < 0) return null;
        final int start = values[idx * 2];
        final int len   = values[idx * 2 + 1];
        return new ByteView() {
            public int length()       { return len; }
            public byte byteAt(int i) { return buffer[start + i]; }
        };
    }

    private int indexOf(String name) {
        for (int i = 0; i < count; i++) {
            if (keyMatches(i, name)) return i;
        }
        return -1;
    }

    /** Case-insensitive comparison between a buffer slice and a String. Zero allocations. */
    private boolean keyMatches(int i, String name) {
        int ks = keys[i * 2], kl = keys[i * 2 + 1];
        if (kl != name.length()) return false;
        for (int j = 0; j < kl; j++) {
            byte b = buffer[ks + j];
            if (b >= 'A' && b <= 'Z') b += 32;
            char c = name.charAt(j);
            if (c >= 'A' && c <= 'Z') c += 32;
            if (b != (byte) c) return false;
        }
        return true;
    }
}
