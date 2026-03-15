package dev.relism.models;

import dev.relism.fpr.core.ByteView;

import java.nio.charset.StandardCharsets;

/**
 * Path parameters captured during routing.
 *
 * <p>Values are stored as byte offsets into the original path view. String conversion
 * is lazy and happens only on {@link #get}; {@link #view} is zero-copy.
 * Lookup is a linear scan — param counts are always small (typically 1–3).
 */
public class PathParams {
    private final ByteView source;
    private final String[] names;
    private final int[]    starts;
    private final int[]    lens;

    public PathParams(ByteView source, String[] names, int[] starts, int[] lens) {
        this.source = source;
        this.names  = names;
        this.starts = starts;
        this.lens   = lens;
    }

    /** Lazy — allocates a {@code String} only on call. {@code null} if the param is absent. */
    public String get(String name) {
        int i = indexOf(name);
        if (i < 0) return null;
        byte[] bytes = new byte[lens[i]];
        for (int j = 0; j < lens[i]; j++) bytes[j] = source.byteAt(starts[i] + j);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** Zero-copy — returns a {@link ByteView} slice over the raw path bytes. */
    ByteView view(String name) {
        int i = indexOf(name);
        if (i < 0) return null;
        final int s = starts[i], l = lens[i];
        return new ByteView() {
            public int  length()        { return l; }
            public byte byteAt(int idx) { return source.byteAt(s + idx); }
        };
    }

    private int indexOf(String name) {
        for (int i = 0; i < names.length; i++) if (names[i].equals(name)) return i;
        return -1;
    }
}
