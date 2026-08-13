package dev.relism.flash.bytes;

/**
 * The allocation-free idiom for returning two {@code int}s from a method without an object:
 * pack both into one {@code long}, unpack at the call site. Already used, hand-rolled, in four
 * places ({@code HeaderMap.findFirst}, {@code QueryParams.findFirst}, and others) before this
 * class existed — this is the single named home for the shifts so they are not duplicated (and
 * potentially inconsistently duplicated — e.g. one copy masking with {@code 0xFFFFFFFFL} and
 * another forgetting to) five times over.
 *
 * <h3>Why this works</h3>
 * A {@code long} is 64 bits; each packed {@code int} is 32. {@link #pack} left-shifts the high
 * half into the top 32 bits and OR's the low half into the bottom 32. {@link #lo} must mask with
 * {@code 0xFFFFFFFFL} rather than simply cast to {@code int} after no mask, because a right-shift
 * of a negative {@code long} sign-extends — the mask discards everything above bit 31 before the
 * narrowing cast happens implicitly. {@link #hi} needs no mask: a right-shift by 32 already
 * leaves only the original high bits in the low 32 positions of the result.
 *
 * <h3>Encoding convention used across this codebase</h3>
 * Every {@code findFirst}-shaped method in this codebase packs {@code (start << 32) | length},
 * i.e. {@code hi() == start} and {@code lo() == length}. {@code -1L} is the shared "not found"
 * sentinel (a valid {@code (start, length)} pair can never be negative, since both halves are
 * non-negative offsets/lengths).
 */
public final class Pairs {
    private Pairs() {}

    /** Packs two {@code int}s into one {@code long}: {@code hi} in the upper 32 bits, {@code lo} in the lower 32. */
    public static long pack(int hi, int lo) {
        return ((long) hi << 32) | (lo & 0xFFFFFFFFL);
    }

    /** Extracts the upper 32 bits packed by {@link #pack}. */
    public static int hi(long packed) {
        return (int) (packed >> 32);
    }

    /** Extracts the lower 32 bits packed by {@link #pack}. */
    public static int lo(long packed) {
        return (int) (packed & 0xFFFFFFFFL);
    }
}
