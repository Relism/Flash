package dev.relism.flash.bytes;

import dev.relism.fpr.core.ByteView;

/**
 * Capability interface for a {@link ByteView} that is a contiguous slice of a single backing
 * {@code byte[]} — as opposed to a {@link SegmentedByteView}, which spans several arrays and
 * cannot expose a single {@code (array, offset)} pair.
 *
 * <p>Every array-backed view in this codebase implements this: {@code RequestByteView},
 * {@code SocketByteView}, {@code StringByteView} (all in
 * {@code dev.relism.flash.bytes.FastPathViews}), and {@link PooledSlice}.
 * {@code MethodPathByteView} deliberately does not — it is a composite of a {@code byte[]}
 * (method) and another {@link ByteView} (path), so it has no single backing array.
 *
 * <h3>What this enables</h3>
 * Anywhere code holds a plain {@link ByteView} and wants the fast path when the concrete
 * instance happens to be array-backed, an {@code instanceof ArrayBackedByteView} check unlocks:
 * <ul>
 *   <li>Single-allocation {@code String} construction —
 *       {@code new String(view.array(), view.offset(), view.length(), UTF_8)} instead of a
 *       byte-at-a-time copy into a scratch {@code byte[]} followed by a second allocation for
 *   <li>A single {@code System.arraycopy} instead of a manual loop wherever a view's bytes need
 *       to be copied.</li>
 * </ul>
 * Code that only has a bare {@link ByteView} (e.g. because it received one across the
 * {@link SegmentedByteView} boundary) keeps the
 * byte-at-a-time fallback — this interface is an opportunistic fast path, never a requirement.
 */
public interface ArrayBackedByteView extends ByteView {
    /** The backing array. Bytes {@code [offset(), offset() + length())} belong to this view. */
    byte[] array();

    /** Offset of this view's first byte within {@link #array()}. */
    int offset();
}
