package dev.relism.flash.bytes;

import dev.relism.fpr.core.ByteView;

/**
 * A {@link ByteView} over up to {@code K} discontiguous {@code byte[]} segments, presented as one
 * logical byte sequence. Exists for the one case in this codebase where a "single contiguous
 * slice of one buffer" model (every other {@link ByteView} implementation) does not hold: an
 * HPACK header block whose encoding spans more than one {@code CONTINUATION} frame (RFC 9113
 * §6.10), where each frame's payload lives in its own connection-buffer region.
 *
 * <h3>Deliberately not array-backed</h3>
 * This does not implement {@link ArrayBackedByteView} — there is no single {@code (array,
 * offset)} pair that describes it — and {@link #supportsLong()} returns {@code false}
 * unconditionally rather than attempting a cross-segment 8-byte read ({@code EX-04}'s word-at-a-
 * time path is only sound for a genuinely contiguous backing array; see
 * {@code FastPathViews.MethodPathByteView} for the other deliberately-segmented view in this
 * codebase, which makes the same choice for the same reason).
 *
 * <h3>Reusable, not allocated per block</h3>
 * {@link #reset} repositions this view over a new set of segments without allocating — the same
 * idiom {@link PooledSlice} uses for the contiguous case. The {@code segments}/{@code offsets}/
 * {@code lengths} arrays passed to {@link #reset} are retained by reference, not copied; the
 * caller owns their lifetime (typically the connection's HPACK scratch, sized to
 * {@code Http2Limits.MAX_CONTINUATION_FRAMES_PER_BLOCK}).
 *
 * <h3>Cost model</h3>
 * {@link #byteAt} walks the segment table to find which segment an index falls in — O(segments),
 * not O(1) — because this view exists precisely for the rare, deliberately-bounded case
 * (at most {@code MAX_CONTINUATION_FRAMES_PER_BLOCK} segments); optimizing it further would add
 * complexity for a path that, by construction, is never hot.
 */
public final class SegmentedByteView implements ByteView {
    private byte[][] segments;
    private int[] offsets;
    private int[] lengths;
    private int count;
    private int totalLength;

    /**
     * Repositions this view over {@code segments[0..count)}, where segment {@code i} contributes
     * bytes {@code segments[i][offsets[i], offsets[i] + lengths[i])}. Zero allocation: the three
     * arrays are retained by reference.
     */
    public void reset(byte[][] segments, int[] offsets, int[] lengths, int count) {
        this.segments = segments;
        this.offsets = offsets;
        this.lengths = lengths;
        this.count = count;
        int total = 0;
        for (int i = 0; i < count; i++) total += lengths[i];
        this.totalLength = total;
    }

    @Override
    public int length() {
        return totalLength;
    }

    @Override
    public byte byteAt(int index) {
        if (index < 0 || index >= totalLength) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + totalLength);
        }
        int remaining = index;
        for (int i = 0; i < count; i++) {
            int len = lengths[i];
            if (remaining < len) {
                return segments[i][offsets[i] + remaining];
            }
            remaining -= len;
        }
        throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + totalLength);
    }

    /** Always {@code false} — see the class Javadoc for why a cross-segment word read is unsound. */
    @Override
    public boolean supportsLong() {
        return false;
    }
}
