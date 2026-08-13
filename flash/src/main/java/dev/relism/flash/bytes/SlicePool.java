package dev.relism.flash.bytes;

/**
 * A small, fixed-size ring of {@link PooledSlice} instances — one per {@code ConnectionScratch}-
 * held call site that used to allocate a fresh {@code ByteView} per call ({@code EX-05}:
 * {@code HeaderMap.view}, {@code QueryParams.view}, {@code PathParams.view}).
 *
 * <h3>Why a ring, not a single reused slice</h3>
 * A single reused slice (the shape {@code HeaderMap.forEach} already uses for its two
 * {@code nameSlice}/{@code valueSlice} fields) is correct only when the caller is guaranteed to
 * finish with one slice before the next is produced — true for a single {@code forEach} callback
 * invocation, false for {@code view()}: a handler might reasonably call
 * {@code headers.view("A")} and {@code headers.view("B")} and want to compare both. A ring of
 * {@code size} slices lets up to {@code size} calls' results stay simultaneously valid.
 *
 * <h3>Lifetime contract</h3>
 * A slice returned by {@link #acquire} is valid until either the request ends, or {@link #acquire}
 * is called {@code size} more times on the same pool (at which point the ring has wrapped around
 * and repositioned that same slot for a new caller) — whichever comes first. This must be
 * restated precisely on every method that hands out a slice from a pool (see
 * {@code HeaderMap.view}'s Javadoc for the canonical wording); it is a real, testable hazard, not
 * a hypothetical one — see {@code SlicePoolTest#wraparoundAliasesThePreviouslyReturnedSlice} for
 * a demonstration.
 */
public final class SlicePool {
    private final PooledSlice[] slices;
    private int next = 0;

    /** A ring of {@code size} reusable slices. {@code size} must be at least 1. */
    public SlicePool(int size) {
        if (size < 1) throw new IllegalArgumentException("SlicePool size must be at least 1: " + size);
        slices = new PooledSlice[size];
        for (int i = 0; i < size; i++) slices[i] = new PooledSlice();
    }

    /** How many slices this pool cycles through before a caller's slice is reused. */
    public int size() {
        return slices.length;
    }

    /**
     * Returns the next slice in the ring, repositioned over {@code array[offset, offset + length)}.
     * Zero allocation — the returned instance already existed.
     */
    public PooledSlice acquire(byte[] array, int offset, int length) {
        PooledSlice slice = slices[next];
        next++;
        if (next == slices.length) next = 0;
        slice.reset(array, offset, length);
        return slice;
    }
}
