package dev.relism.flash.models;

import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.flash.bytes.SlicePool;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.fpr.core.ByteView;

import java.nio.charset.StandardCharsets;

/**
 * Path parameters captured during routing, stored as byte offsets into the path view.
 * {@link #get} allocates a {@code String} on call (in one allocation when {@link #source} is
 * {@link ArrayBackedByteView} — {@code EX-25} — two otherwise); {@link #view} is zero-copy.
 *
 * <h3>Reusable instances ({@code EX-19})</h3>
 * The public constructor below builds a one-shot, fixed-size instance (used by
 * {@code AbstractWsRouter} and by tests) — {@code names.length} is taken as the exact param
 * count. {@code FastPathRouterImpl}'s per-connection scratch instead owns a single long-lived
 * {@code PathParams} whose backing arrays are grown to the connection's high-water mark and
 * never reallocated after warmup; because those arrays can be larger than the current request's
 * actual param count, that path uses {@link #reset}, which — unlike the constructor — takes the
 * live count explicitly rather than inferring it from array length. Both this constructor and
 * {@link #reset} are {@code public} rather than package-private (matching
 * {@link HeaderMap#reset}'s own precedent for a reusable buffer-backed object): the router
 * implementation that owns the reusable instance lives in a different package
 * ({@code dev.relism.flash.routing.routers.fastpathrouter}), and {@code PathParams.inject}'s
 * own doc explains why this codebase prefers a small public surface here over a cross-package
 * friend-access workaround. A {@code PathParams} obtained this way has the same "do not retain
 * past the handler" lifetime contract as {@link HeaderMap}'s buffer-backed views: the next
 * request on the same connection repositions the same arrays.
 */
public class PathParams {
    private static final int VIEW_POOL_SIZE = 4;

    private ByteView source;
    private final String[] names;
    private final int[]    starts;
    private final int[]    lens;
    private int count;

    // EX-05: created lazily, only if view() is ever actually called.
    private SlicePool viewPool;

    public PathParams(ByteView source, String[] names, int[] starts, int[] lens) {
        this.source = source;
        this.names  = names;
        this.starts = starts;
        this.lens   = lens;
        this.count  = names.length;
    }

    /**
     * Builds an instance meant only for {@link #reset}: no source yet, and {@code count} starts
     * at 0 until the first {@link #reset} call. {@code names}/{@code starts}/{@code lens} may be
     * larger than any single request's param count — see the class Javadoc.
     */
    public PathParams(String[] names, int[] starts, int[] lens) {
        this.source = null;
        this.names  = names;
        this.starts = starts;
        this.lens   = lens;
        this.count  = 0;
    }

    /**
     * Repositions this instance over a new request: {@code count} (which may be less than
     * {@code names.length} — see the class Javadoc) params are now valid, read out of the same
     * backing arrays the constructor was given, against the new {@code source}. Zero allocation.
     */
    public void reset(ByteView source, int count) {
        this.source = source;
        this.count  = count;
    }

    /**
     * Injects path parameters into a request. The cross-package bridge from
     * {@link AbstractRouter} — keeps {@link Request#setPathParams}
     * package-private while allowing the router to set params without a public setter.
     */
    public static void inject(Request request, PathParams params) {
        request.setPathParams(params);
    }

    public String get(String name) {
        int i = indexOf(name);
        if (i < 0) return null;
        int start = starts[i], len = lens[i];
        // EX-25: a single-copy String construction when the source is a contiguous array slice
        // (always true for h1 today) instead of a byte-at-a-time copy into a scratch array
        // followed by a second allocation for the String itself.
        if (source instanceof ArrayBackedByteView abv) {
            return new String(abv.array(), abv.offset() + start, len, StandardCharsets.UTF_8);
        }
        byte[] bytes = new byte[len];
        for (int j = 0; j < len; j++) bytes[j] = source.byteAt(start + j);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Returns a zero-copy view over path param {@code name}, or {@code null}. {@code EX-05}:
     * drawn from a small internal {@link SlicePool} when {@link #source} is array-backed (always
     * true for h1 today) — same reuse-window contract as {@link HeaderMap#view}. Falls back to a
     * fresh (allocating) view otherwise — never exercised on the real request path.
     */
    ByteView view(String name) {
        int i = indexOf(name);
        if (i < 0) return null;
        int s = starts[i], l = lens[i];
        if (source instanceof ArrayBackedByteView abv) {
            if (viewPool == null) viewPool = new SlicePool(VIEW_POOL_SIZE);
            return viewPool.acquire(abv.array(), abv.offset() + s, l);
        }
        final int fs = s, fl = l;
        return new ByteView() {
            public int  length()        { return fl; }
            public byte byteAt(int idx) { return source.byteAt(fs + idx); }
        };
    }

    private int indexOf(String name) {
        for (int i = 0; i < count; i++) if (names[i].equals(name)) return i;
        return -1;
    }
}
