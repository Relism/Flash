package dev.relism.flash.template;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Precompiled, allocation-minimal byte template.
 * <p>
 * Placeholders of the form {@code {{name}}} are detected once at construction.
 * Each {@link #render} call makes exactly one allocation: the output byte[]
 * (plus one {@code byte[]} per distinct key-value pair, for its UTF-8 bytes).
 * <p>
 * Layout: seg[0] slot[0] seg[1] slot[1] … seg[n-1] slot[n-1] seg[n]
 *
 * <h3>{@code EX-28}: slot lookup is O(1) per key-value pair, not O(slots)</h3>
 * A slot name can appear more than once (e.g. {@code {{var}} == {{var}}}), so the map built at
 * construction maps each name to the (usually single-element) array of every slot index using
 * that name, instead of the nested "scan every slot for every pair" loop this used to do.
 */
public final class ByteTemplate {

    private final byte[][] segments;         // literal byte segments
    private final String[] slots;            // placeholder names in order
    private final int staticLength;          // sum of all segment lengths (precomputed)
    private final Map<String, int[]> slotIndex; // slot name -> every slot index using that name

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

        Map<String, List<Integer>> byName = new HashMap<>();
        for (int j = 0; j < slots.length; j++) {
            byName.computeIfAbsent(slots[j], k -> new ArrayList<>()).add(j);
        }
        Map<String, int[]> idx = new HashMap<>();
        for (Map.Entry<String, List<Integer>> e : byName.entrySet()) {
            int[] arr = new int[e.getValue().size()];
            for (int j = 0; j < arr.length; j++) arr[j] = e.getValue().get(j);
            idx.put(e.getKey(), arr);
        }
        slotIndex = idx;
    }

    /**
     * Render with alternating key-value String pairs: {@code k1, v1, k2, v2, …}
     * Unmatched slots are rendered as empty. Allocates the returned {@code byte[]}; for a
     * caller-supplied buffer see {@link #renderInto(byte[], int, String...)}.
     */
    public byte[] render(String... kvPairs) {
        byte[][] values = resolveValues(kvPairs);
        byte[] out = new byte[length(values)];
        writeInto(out, 0, values);
        return out;
    }

    /**
     * Renders into {@code buffer} starting at {@code offset}, making no allocation beyond the
     * per-pair UTF-8 conversion of {@code kvPairs}' values. Returns the number of bytes written.
     *
     * @throws IndexOutOfBoundsException if {@code buffer} does not have enough room from {@code offset}
     */
    public int renderInto(byte[] buffer, int offset, String... kvPairs) {
        byte[][] values = resolveValues(kvPairs);
        int len = length(values);
        if (offset < 0 || offset + len > buffer.length) {
            throw new IndexOutOfBoundsException(
                    "buffer too small: need " + len + " bytes at offset " + offset + ", have " + (buffer.length - offset));
        }
        writeInto(buffer, offset, values);
        return len;
    }

    private byte[][] resolveValues(String[] kvPairs) {
        byte[][] values = new byte[slots.length][];
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            int[] matches = slotIndex.get(kvPairs[i]);
            if (matches == null) continue;
            byte[] val = kvPairs[i + 1].getBytes(StandardCharsets.UTF_8);
            for (int idx : matches) values[idx] = val;
        }
        return values;
    }

    private int length(byte[][] values) {
        int len = staticLength;
        for (byte[] v : values) if (v != null) len += v.length;
        return len;
    }

    private void writeInto(byte[] out, int offset, byte[][] values) {
        int pos = offset;
        for (int i = 0; i < slots.length; i++) {
            System.arraycopy(segments[i], 0, out, pos, segments[i].length);
            pos += segments[i].length;
            if (values[i] != null) {
                System.arraycopy(values[i], 0, out, pos, values[i].length);
                pos += values[i].length;
            }
        }
        System.arraycopy(segments[slots.length], 0, out, pos, segments[slots.length].length);
    }
}
