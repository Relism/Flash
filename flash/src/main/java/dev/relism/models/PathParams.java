package dev.relism.models;

import dev.relism.fpr.core.ByteView;

import java.nio.charset.StandardCharsets;

/**
 * Path parameters captured during routing, stored as byte offsets into the path view.
 * {@link #get} allocates a String on call; {@link #view} is zero-copy.
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

    /**
     * Injects path parameters into a request. The cross-package bridge from
     * {@link dev.relism.routing.AbstractRouter} — keeps {@link Request#setPathParams}
     * package-private while allowing the router to set params without a public setter.
     */
    public static void inject(Request request, PathParams params) {
        request.setPathParams(params);
    }

    public String get(String name) {
        int i = indexOf(name);
        if (i < 0) return null;
        byte[] bytes = new byte[lens[i]];
        for (int j = 0; j < lens[i]; j++) bytes[j] = source.byteAt(starts[i] + j);
        return new String(bytes, StandardCharsets.UTF_8);
    }

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
