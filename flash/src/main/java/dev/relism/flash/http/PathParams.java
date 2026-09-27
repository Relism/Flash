package dev.relism.flash.http;

import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.flash.bytes.Pairs;
import dev.relism.fpr.core.ByteView;

import java.nio.charset.StandardCharsets;

/**
 * Path parameters as offsets into the path's bytes. The router keeps one instance per connection,
 * grown to the widest route it has seen and {@link #reset} for every request; a {@code String} is
 * made only when {@link #get} is called. Valid while the handler runs, like the request itself.
 */
public class PathParams {

    private ByteView source;
    private final String[] names;
    private final int[] starts;
    private final int[] lens;
    private int count;

    /** Every parameter in the arrays is valid, over {@code source}: a one-off instance. */
    public PathParams(ByteView source, String[] names, int[] starts, int[] lens) {
        this(names, starts, lens);
        reset(source, names.length);
    }

    /** Arrays the router fills; none is valid until {@link #reset}. */
    public PathParams(String[] names, int[] starts, int[] lens) {
        this.names = names;
        this.starts = starts;
        this.lens = lens;
    }

    /** The first {@code count} entries are now valid, over {@code source}. */
    public void reset(ByteView source, int count) {
        this.source = source;
        this.count = count;
    }

    /** Hands {@code params} to {@code request}; how the router, in another package, sets them. */
    public static void inject(Request request, PathParams params) {
        request.setPathParams(params);
    }

    public String get(String name) {
        int i = indexOf(name);
        if (i < 0) return null;
        int start = starts[i], len = lens[i];
        if (source instanceof ArrayBackedByteView array)
            return new String(array.array(), array.offset() + start, len, StandardCharsets.UTF_8);
        byte[] bytes = new byte[len];
        for (int j = 0; j < len; j++) bytes[j] = source.byteAt(start + j);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Where {@code name} sits in {@link #source()}, packed by {@link Pairs}, or -1. */
    long range(String name) {
        int i = indexOf(name);
        return i < 0 ? -1L : Pairs.pack(starts[i], lens[i]);
    }

    ByteView source() {
        return source;
    }

    private int indexOf(String name) {
        for (int i = 0; i < count; i++) if (names[i].equals(name)) return i;
        return -1;
    }
}
