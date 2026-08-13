package dev.relism.flash.bytes;

/**
 * A mutable, reusable {@link ArrayBackedByteView} — the {@code EX-05} fix. Replaces the
 * per-call {@code new ByteView() { ... }} anonymous-class allocation that used to live in
 * {@code HeaderMap.view}, {@code QueryParams.view}, and {@code PathParams.view}: instead of
 * allocating a fresh view object (plus its capturing instance) on every call, a small
 * {@link SlicePool} of these hands out an existing instance, repositioned in place.
 *
 * <h3>Lifetime contract</h3>
 * A {@code PooledSlice} handed out by {@link SlicePool#acquire} is valid only until the pool
 * wraps around and reuses the same slot — see {@link SlicePool}'s own Javadoc for the exact
 * "valid until the Nth subsequent acquire, or end of request" rule the owning class (e.g.
 * {@code HeaderMap}) documents precisely for its own {@code view()} method. Never retain a
 * {@code PooledSlice} past that window, for the same reason the old anonymous view could not be
 * retained past the handler: the bytes (and, here, additionally the slice object itself) are
 * about to be repositioned out from under a stale reference.
 */
public final class PooledSlice implements ArrayBackedByteView {
    private byte[] array;
    private int offset;
    private int length;

    /** Repositions this slice over {@code array[offset, offset + length)}. Zero allocation. */
    public void reset(byte[] array, int offset, int length) {
        this.array = array;
        this.offset = offset;
        this.length = length;
    }

    @Override
    public byte[] array() {
        return array;
    }

    @Override
    public int offset() {
        return offset;
    }

    @Override
    public int length() {
        return length;
    }

    @Override
    public byte byteAt(int index) {
        if (index < 0 || index >= length) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + length);
        }
        return array[offset + index];
    }
}
