package dev.relism.flash.template;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Precompiled, allocation-minimal byte template.
 * <p>
 * Placeholders of the form {@code {{name}}} are detected once at construction.
 * Each {@link #render} call makes exactly one allocation: the output byte[].
 * <p>
 * Layout: seg[0] slot[0] seg[1] slot[1] … seg[n-1] slot[n-1] seg[n]
 */
public final class ByteTemplate {

    private final byte[][] segments;  // literal byte segments
    private final String[] slots;     // placeholder names in order
    private final int staticLength;   // sum of all segment lengths (precomputed)

    public ByteTemplate(String source) {
        List<byte[]> segs = new ArrayList<>();
        List<String> slts = new ArrayList<>();
        int start = 0, i = 0;
        while (i < source.length()) {
            if (source.charAt(i) == '{' && i + 1 < source.length() && source.charAt(i + 1) == '{') {
                int end = source.indexOf("}}", i + 2);
                if (end < 0) break;
                segs.add(source.substring(start, i).getBytes(StandardCharsets.UTF_8));
                slts.add(source.substring(i + 2, end));
                start = end + 2;
                i = end + 2;
            } else {
                i++;
            }
        }
        segs.add(source.substring(start).getBytes(StandardCharsets.UTF_8));
        segments = segs.toArray(new byte[0][]);
        slots = slts.toArray(new String[0]);
        int sl = 0;
        for (byte[] s : segments) sl += s.length;
        staticLength = sl;
    }

    /**
     * Render with alternating key-value String pairs: {@code k1, v1, k2, v2, …}
     * Unmatched slots are rendered as empty.
     */
    public byte[] render(String... kvPairs) {
        byte[][] values = new byte[slots.length][];
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            String key = kvPairs[i];
            byte[] val = kvPairs[i + 1].getBytes(StandardCharsets.UTF_8);
            for (int j = 0; j < slots.length; j++) {
                if (slots[j].equals(key)) { values[j] = val; }
            }
        }

        int len = staticLength;
        for (byte[] v : values) if (v != null) len += v.length;

        byte[] out = new byte[len];
        int pos = 0;
        for (int i = 0; i < slots.length; i++) {
            System.arraycopy(segments[i], 0, out, pos, segments[i].length);
            pos += segments[i].length;
            if (values[i] != null) {
                System.arraycopy(values[i], 0, out, pos, values[i].length);
                pos += values[i].length;
            }
        }
        System.arraycopy(segments[slots.length], 0, out, pos, segments[slots.length].length);
        return out;
    }
}
