package dev.relism.flash.models;

import dev.relism.flash.bytes.ByteScan;
import dev.relism.flash.bytes.SlicePool;
import dev.relism.flash.http.Http1Limits;
import dev.relism.fpr.core.ByteView;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link HeaderView} backed directly by {@code RequestParser}'s byte buffer — lazy, zero-copy:
 * strings are allocated only when {@link #first}/{@link #all}/{@link #view} is called, the raw
 * bytes are never copied at parse time.
 *
 * <h3>Package placement</h3>
 * Despite the {@code Http1} prefix, this class lives in {@code dev.relism.flash.models}, not
 * {@code dev.relism.flash.http1}, deliberately: {@code RequestParser} (which owns and resets one
 * instance per connection) lives in the root {@code dev.relism.flash} package, and {@code http1}
 * already depends on root (via {@code Http1Connection}'s use of {@code RequestParser}) — placing
 * this class in {@code http1} would require root to import back from {@code http1}, the exact
 * kind of package cycle {@code DEC-19} already found and avoided once in this codebase. See
 * {@code DECISIONS.md}, {@code DEC-22}, for the full reasoning; this note exists so a future
 * reader does not "fix" the location back to what the plan's Files list originally suggested.
 *
 * <h3>Lifetime contract — read carefully</h3>
 * One {@code Http1HeaderMap} instance lives on the connection (not per-request). On every
 * keep-alive request {@link #reset} is called to slide the window over the new header
 * section of the <em>same reused buffer</em>. This has two critical implications:
 *
 * <ol>
 *   <li><b>Do not retain the {@code Http1HeaderMap} beyond the handler.</b> After the handler
 *       returns, the next request reuses and overwrites the buffer. Any {@code String}
 *       values retrieved via {@link #first}/{@link #all} are safe (they are independent
 *       heap copies); the {@code Http1HeaderMap} object itself is not.</li>
 *   <li><b>{@link #view} returns a zero-copy {@link dev.relism.fpr.core.ByteView} slice
 *       into the live buffer, drawn from a small {@link SlicePool} (see {@link #view}'s own
 *       Javadoc for the exact reuse window).</b> Storing this view and reading it after the
 *       handler returns (e.g. in an async callback, a {@link java.util.concurrent.CompletableFuture}
 *       continuation, or a virtual-thread handoff) is a <em>data race</em> — the bytes
 *       may have been overwritten by the next request. Copy to a {@code String} or
 *       {@code byte[]} before leaving the synchronous handler scope.</li>
 * </ol>
 *
 * <h3>{@code EX-09}: an index built once per {@link #reset}, not rescanned per lookup</h3>
 * {@link #reset} scans the header section exactly once and records, per header, its name/value
 * byte offsets and a case-insensitive 32-bit hash of the name — into {@code int[]} arrays grown
 * (never shrunk) to this connection's high-water mark. Every lookup method
 * ({@link #first}, {@link #all}, {@link #view}, {@link #valueEqualsIgnoreCase}) then walks that
 * small index instead of rescanning raw bytes: a hash compare (cheap) before ever falling back to
 * a full case-insensitive name comparison.
 */
@NoArgsConstructor
public class Http1HeaderMap implements HeaderView {
    private static final int INITIAL_INDEX_CAPACITY = 16;
    private static final int VIEW_POOL_SIZE = 4;

    private byte[] buffer;
    private int sectionStart;
    private int sectionEnd;

    // EX-09 index — grown (never shrunk) to this connection's high-water mark, rebuilt in place
    // by every reset() call. Entry i's name is buffer[nameOffsets[i], nameOffsets[i]+nameLengths[i]),
    // its value is buffer[valueOffsets[i], valueOffsets[i]+valueLengths[i]).
    private int   headerCount;
    private int[] nameOffsets  = new int[INITIAL_INDEX_CAPACITY];
    private int[] nameLengths  = new int[INITIAL_INDEX_CAPACITY];
    private int[] valueOffsets = new int[INITIAL_INDEX_CAPACITY];
    private int[] valueLengths = new int[INITIAL_INDEX_CAPACITY];
    private int[] nameHashes   = new int[INITIAL_INDEX_CAPACITY];

    // EX-05: pooled, reused slices for view() — see its own Javadoc for the reuse window.
    private final SlicePool viewPool = new SlicePool(VIEW_POOL_SIZE);

    // forEach's own pair, reused across every header of every call — same idiom as viewPool,
    // just a fixed pair rather than a ring, since forEach's contract only needs one name/value
    // pair valid at a time (see forEach's Javadoc).
    private Slice nameSlice;
    private Slice valueSlice;

    /** Resets this map to the header section {@code buffer[sectionStart, sectionEnd)}, rebuilding the {@code EX-09} index. */
    public void reset(byte[] buffer, int sectionStart, int sectionEnd) {
        this.buffer       = buffer;
        this.sectionStart = sectionStart;
        this.sectionEnd   = sectionEnd;
        buildIndex();
    }

    private void buildIndex() {
        headerCount = 0;
        if (buffer == null) return;
        int i = sectionStart;
        while (i < sectionEnd) {
            int lineEnd = findCR(i);
            int colon   = findColon(i, lineEnd);
            if (colon != -1) {
                int vs = skipSpaces(colon + 1, lineEnd);
                ensureIndexCapacity(headerCount + 1);
                nameOffsets[headerCount]  = i;
                nameLengths[headerCount]  = colon - i;
                valueOffsets[headerCount] = vs;
                valueLengths[headerCount] = lineEnd - vs;
                nameHashes[headerCount]   = ByteScan.hashNameIgnoreCaseAscii(buffer, i, colon - i);
                headerCount++;
            }
            i = lineEnd + 2;
        }
    }

    private void ensureIndexCapacity(int needed) {
        if (needed <= nameOffsets.length) return;
        // EX-08 (Http1Limits.MAX_HEADER_COUNT) already rejects any request with more headers
        // than this before it ever reaches reset() — this can only fire while growing toward
        // that ceiling, never past it. Asserted, not silently truncated: an index that silently
        // dropped headers past this point would be a correctness bug, not a capacity one.
        assert needed <= Http1Limits.MAX_HEADER_COUNT
                : "header count " + needed + " exceeds Http1Limits.MAX_HEADER_COUNT — RequestParser should have rejected this already";
        int grown = nameOffsets.length;
        while (grown < needed) grown *= 2;
        nameOffsets  = Arrays.copyOf(nameOffsets, grown);
        nameLengths  = Arrays.copyOf(nameLengths, grown);
        valueOffsets = Arrays.copyOf(valueOffsets, grown);
        valueLengths = Arrays.copyOf(valueLengths, grown);
        nameHashes   = Arrays.copyOf(nameHashes, grown);
    }

    @Override
    public void forEach(HeaderConsumer consumer) {
        if (buffer == null) return;
        if (nameSlice == null) {
            nameSlice  = new Slice();
            valueSlice = new Slice();
        }
        for (int i = 0; i < headerCount; i++) {
            nameSlice.start  = nameOffsets[i];
            nameSlice.len    = nameLengths[i];
            valueSlice.start = valueOffsets[i];
            valueSlice.len   = valueLengths[i];
            consumer.accept(nameSlice, valueSlice);
        }
    }

    /** Mutable zero-copy slice into {@link #buffer} — see {@link #forEach}. */
    private final class Slice implements ByteView {
        int start;
        int len;

        @Override public int length()       { return len; }
        @Override public byte byteAt(int i) { return buffer[start + i]; }
    }

    @Override
    public String first(String name) {
        int i = indexOfHeader(name);
        if (i < 0) return null;
        return new String(buffer, valueOffsets[i], valueLengths[i], StandardCharsets.UTF_8);
    }

    @Override
    public List<String> all(String name) {
        if (buffer == null) return List.of();
        List<String> result = null;
        int hash = ByteScan.hashNameIgnoreCaseAscii(name);
        for (int i = 0; i < headerCount; i++) {
            if (nameHashes[i] == hash && ByteScan.equalsIgnoreCaseAscii(buffer, nameOffsets[i], nameOffsets[i] + nameLengths[i], name)) {
                if (result == null) result = new ArrayList<>();
                result.add(new String(buffer, valueOffsets[i], valueLengths[i], StandardCharsets.UTF_8));
            }
        }
        return result != null ? result : List.of();
    }

    @Override
    public List<String> all() {
        if (buffer == null) return List.of();
        List<String> result = new ArrayList<>(headerCount);
        for (int i = 0; i < headerCount; i++) {
            result.add(new String(buffer, valueOffsets[i], valueLengths[i], StandardCharsets.UTF_8));
        }
        return result;
    }

    @Override
    public boolean valueEqualsIgnoreCase(String name, String value) {
        int i = indexOfHeader(name);
        if (i < 0) return false;
        return ByteScan.equalsIgnoreCaseAscii(buffer, valueOffsets[i], valueOffsets[i] + valueLengths[i], value);
    }

    @Override
    public boolean contains(String name) {
        return indexOfHeader(name) >= 0;
    }

    @Override
    public int count() {
        return headerCount;
    }

    /**
     * Returns a zero-copy {@link ByteView} over the first value of {@code name}, or {@code null}.
     *
     * <h3>{@code EX-05}: pooled, not allocated per call</h3>
     * The returned view is drawn from a small internal {@link SlicePool} rather than allocated
     * fresh. It stays valid until either the request ends, or {@link #view} is called
     * {@value #VIEW_POOL_SIZE} more times on this same {@code Http1HeaderMap} — whichever comes
     * first — at which point the ring wraps around and silently repositions the same instance
     * over different bytes. A handler that needs more than {@value #VIEW_POOL_SIZE} views alive
     * at once should copy the earlier ones to {@code String}/{@code byte[]} before requesting more.
     */
    @Override
    public ByteView view(String name) {
        int i = indexOfHeader(name);
        if (i < 0) return null;
        return viewPool.acquire(buffer, valueOffsets[i], valueLengths[i]);
    }

    /** Index into the {@code EX-09} arrays of the first header named {@code name}, or {@code -1}. */
    private int indexOfHeader(String name) {
        if (buffer == null) return -1;
        int hash = ByteScan.hashNameIgnoreCaseAscii(name);
        for (int i = 0; i < headerCount; i++) {
            if (nameHashes[i] == hash && ByteScan.equalsIgnoreCaseAscii(buffer, nameOffsets[i], nameOffsets[i] + nameLengths[i], name)) {
                return i;
            }
        }
        return -1;
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
}
