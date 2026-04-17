package dev.relism.models;

import dev.relism.fpr.core.ByteView;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Lazy, zero-copy header access backed directly by the request parser's byte buffer.
 * Strings are allocated only when {@link #first} / {@link #all} / {@link #view} is called;
 * the raw bytes are never copied at parse time.
 *
 * <h3>Lifetime contract — read carefully</h3>
 * One {@code HeaderMap} instance lives on the connection (not per-request). On every
 * keep-alive request {@link #reset} is called to slide the window over the new header
 * section of the <em>same reused buffer</em>. This has two critical implications:
 *
 * <ol>
 *   <li><b>Do not retain the {@code HeaderMap} beyond the handler.</b> After the handler
 *       returns, the next request reuses and overwrites the buffer. Any {@code String}
 *       values retrieved via {@link #first}/{@link #all} are safe (they are independent
 *       heap copies); the {@code HeaderMap} object itself is not.</li>
 *   <li><b>{@link #view} returns a zero-copy {@link dev.relism.fpr.core.ByteView} slice
 *       into the live buffer.</b> Storing this view and reading it after the handler
 *       returns (e.g. in an async callback, a {@link java.util.concurrent.CompletableFuture}
 *       continuation, or a virtual-thread handoff) is a <em>data race</em> — the bytes
 *       may have been overwritten by the next request. Copy to a {@code String} or
 *       {@code byte[]} before leaving the synchronous handler scope.</li>
 * </ol>
 */
@NoArgsConstructor
public class HeaderMap {
    private byte[] buffer;
    private int sectionStart;
    private int sectionEnd;

    /** Resets this map to the header section {@code buffer[sectionStart, sectionEnd)}. */
    public void reset(byte[] buffer, int sectionStart, int sectionEnd) {
        this.buffer       = buffer;
        this.sectionStart = sectionStart;
        this.sectionEnd   = sectionEnd;
    }

    /** Returns the first value of header {@code name} (case-insensitive), or {@code null}. */
    public String first(String name) {
        long r = findFirst(name);
        if (r < 0) return null;
        int s = (int) (r >> 32), l = (int) (r & 0xFFFFFFFFL);
        return new String(buffer, s, l, StandardCharsets.UTF_8);
    }

    /** Returns all values of header {@code name} in declaration order, or an empty list. */
    public List<String> all(String name) {
        if (buffer == null) return List.of();
        List<String> result = null;
        int i = sectionStart;
        while (i < sectionEnd) {
            int lineEnd = findCR(i);
            int colon   = findColon(i, lineEnd);
            if (colon != -1 && keyMatches(i, colon - i, name)) {
                int vs = skipSpaces(colon + 1, lineEnd);
                if (result == null) result = new ArrayList<>();
                result.add(new String(buffer, vs, lineEnd - vs, StandardCharsets.UTF_8));
            }
            i = lineEnd + 2;
        }
        return result != null ? result : List.of();
    }

    /** Returns all header values in declaration order. */
    public List<String> all() {
        if (buffer == null) return List.of();
        List<String> result = new ArrayList<>();
        int i = sectionStart;
        while (i < sectionEnd) {
            int lineEnd = findCR(i);
            int colon   = findColon(i, lineEnd);
            if (colon != -1) {
                int vs = skipSpaces(colon + 1, lineEnd);
                result.add(new String(buffer, vs, lineEnd - vs, StandardCharsets.UTF_8));
            }
            i = lineEnd + 2;
        }
        return result;
    }

    /** Case-insensitive comparison of the first value of {@code name} against {@code value}. */
    public boolean valueEqualsIgnoreCase(String name, String value) {
        long r = findFirst(name);
        if (r < 0) return false;
        int vs = (int) (r >> 32), vl = (int) (r & 0xFFFFFFFFL);
        if (vl != value.length()) return false;
        for (int i = 0; i < vl; i++) {
            byte b = buffer[vs + i];
            if (b >= 'A' && b <= 'Z') b += 32;
            char c = value.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 32;
            if (b != (byte) c) return false;
        }
        return true;
    }

    /** Returns a zero-copy {@link ByteView} over the first value of {@code name}, or {@code null}. */
    public ByteView view(String name) {
        long r = findFirst(name);
        if (r < 0) return null;
        final int s = (int) (r >> 32), l = (int) (r & 0xFFFFFFFFL);
        return new ByteView() {
            public int length()       { return l; }
            public byte byteAt(int i) { return buffer[s + i]; }
        };
    }

    /** Returns {@code (valStart << 32) | valLen}, or {@code -1} if not found. */
    private long findFirst(String name) {
        if (buffer == null) return -1L;
        int i = sectionStart;
        while (i < sectionEnd) {
            int lineEnd = findCR(i);
            int colon   = findColon(i, lineEnd);
            if (colon != -1 && keyMatches(i, colon - i, name)) {
                int vs = skipSpaces(colon + 1, lineEnd);
                return ((long) vs << 32) | (lineEnd - vs);
            }
            i = lineEnd + 2;
        }
        return -1L;
    }

    private int findCR(int from) {
        for (int i = from; i < sectionEnd; i++) if (buffer[i] == '\r') return i;
        return sectionEnd;
    }

    private int findColon(int from, int to) {
        for (int i = from; i < to; i++) if (buffer[i] == ':') return i;
        return -1;
    }

    private int skipSpaces(int from, int end) {
        while (from < end && buffer[from] == ' ') from++;
        return from;
    }

    private boolean keyMatches(int start, int len, String name) {
        if (len != name.length()) return false;
        for (int i = 0; i < len; i++) {
            byte b = buffer[start + i];
            if (b >= 'A' && b <= 'Z') b += 32;
            char c = name.charAt(i);
            if (c >= 'A' && c <= 'Z') c += 32;
            if (b != (byte) c) return false;
        }
        return true;
    }
}
