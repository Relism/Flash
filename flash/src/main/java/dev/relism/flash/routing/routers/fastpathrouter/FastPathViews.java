package dev.relism.flash.routing.routers.fastpathrouter;

import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.fpr.core.ByteView;
import lombok.NoArgsConstructor;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/** {@link dev.relism.fpr.core.ByteView} implementations used on the router and parser hot paths. */
@NoArgsConstructor
public final class FastPathViews {

    /**
     * {@code EX-04}: {@code fpr-core}'s decompiled {@code ByteCompare} (its word-at-a-time
     * router-matching fast path — see {@code ByteCompare.equals}/{@code indexOf}) reads a
     * comparison word via {@code MethodHandles.byteArrayViewVarHandle(long[].class,
     * ByteOrder.LITTLE_ENDIAN)} and compares it bit-for-bit against whatever
     * {@link ByteView#longAt} returns. For that comparison to be correct, {@code longAt} must
     * therefore return the <em>identical</em> little-endian-assembled value for the same 8
     * bytes — fixed to {@code LITTLE_ENDIAN} specifically (not {@code nativeOrder()}) so the
     * contract holds on every host regardless of the JVM's native byte order, matching
     * {@code ByteCompare}'s own fixed choice exactly. Confirmed by decompiling
     * {@code fpr-core-1.1.1}'s {@code ByteCompare.class} (its {@code LONG_VIEW} field), not
     * merely assumed — see {@code FastPathViewsLongAtTest} for the runtime verification the
     * plan requires beyond reading bytecode.
     */
    private static final VarHandle LONG_VIEW_LE =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);

    /**
     * Reads 8 bytes at {@code array[pos, pos + 8)} as fpr-core's {@code ByteCompare} expects a
     * {@link ByteView#longAt} implementation to. Caller-guaranteed contract (never asserted here
     * — {@code ByteCompare} itself never calls this without first checking {@code pos + 8 <=
     * length}, so a defensive check here would be dead code on every real call path; see
     * {@code EX-04}'s registry entry): {@code pos + 8 <= array.length}.
     */
    private static long longAtLittleEndian(byte[] array, int pos) {
        return (long) LONG_VIEW_LE.get(array, pos);
    }

    /**
     * {@code EX-42}: not immutable — {@link #reset} repositions an existing instance over new
     * bounds instead of requiring a fresh allocation. {@code RequestParser} owns one pooled
     * instance per role (path/query/protocol) per connection and calls {@link #reset} on it for
     * every request, the same "do not retain past the handler" pooling contract every other
     * per-connection object in this codebase already follows ({@code Http1HeaderMap},
     * {@code RequestLine}, {@code Request}, {@code RequestBody}). The public constructor remains
     * for one-shot, non-pooled use (tests, other call sites that build a single fixed view).
     */
    public static final class RequestByteView implements ArrayBackedByteView {
        private byte[] buffer;
        private int start;
        private int length;

        public RequestByteView(byte[] buffer, int start, int length) {
            this.buffer = buffer;
            this.start = start;
            this.length = length;
        }

        /** Repositions this instance over new bounds. Zero allocation. */
        public void reset(byte[] buffer, int start, int length) {
            this.buffer = buffer;
            this.start = start;
            this.length = length;
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
            return buffer[start + index];
        }

        @Override
        public byte[] array() {
            return buffer;
        }

        @Override
        public int offset() {
            return start;
        }

        /** {@code EX-04}: array-backed and contiguous — the word-at-a-time router path applies. */
        @Override
        public boolean supportsLong() {
            return true;
        }

        @Override
        public long longAt(int index) {
            return longAtLittleEndian(buffer, start + index);
        }

        @Override
        public String toString() {
            return new String(buffer, start, length, StandardCharsets.UTF_8);
        }
    }

    /**
     * Mutable composite view: method bytes + path. Reused per connection, call {@link #reset}
     * before use (see {@code FastPathRouterImpl}'s per-connection scratch, {@code EX-06}).
     *
     * <h3>{@code EX-04}: deliberately not array-backed, {@code supportsLong()} stays {@code false}</h3>
     * Unlike every other view in this file, this one is a composite of two independent sources
     * (a raw {@code byte[]} for the method, and another {@link ByteView} — itself possibly
     * array-backed — for the path). There is no single backing array a word-at-a-time read could
     * span, and a byte index near the method/path boundary could straddle both sources entirely,
     * making a single contiguous 8-byte read structurally impossible in general (not merely
     * unimplemented) — the same reasoning {@link dev.relism.flash.bytes.SegmentedByteView}
     * documents for the analogous HPACK CONTINUATION case. Falls back to the inherited
     * {@link ByteView#supportsLong} default ({@code false}); {@code fpr-core}'s router-matching
     * path already handles that correctly (it only takes the word-at-a-time branch when
     * {@code supportsLong()} is {@code true}).
     */
    public static final class MethodPathByteView implements ByteView {
        private byte[] method;
        private ByteView path;
        private int totalLength;

        public void reset(byte[] method, ByteView path) {
            this.method = method;
            this.path = path;
            this.totalLength = method.length + path.length();
        }

        @Override
        public int length() {
            return totalLength;
        }

        @Override
        public byte byteAt(int index) {
            return index < method.length ? method[index] : path.byteAt(index - method.length);
        }
    }

    public static class SocketByteView implements ArrayBackedByteView {
        private final byte[] data;

        public SocketByteView(byte[] data) {
            this.data = data;
        }

        @Override
        public int length() {
            return data.length;
        }

        @Override
        public byte byteAt(int index) {
            return data[index];
        }

        @Override
        public byte[] array() {
            return data;
        }

        @Override
        public int offset() {
            return 0;
        }

        /** {@code EX-04}: array-backed and contiguous — the word-at-a-time router path applies. */
        @Override
        public boolean supportsLong() {
            return true;
        }

        @Override
        public long longAt(int index) {
            return longAtLittleEndian(data, index);
        }
    }

    public static class StringByteView implements ArrayBackedByteView {
        private final byte[] bytes;

        public StringByteView(String str) {
            this.bytes = str.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int length() {
            return bytes.length;
        }

        @Override
        public byte byteAt(int index) {
            return bytes[index];
        }

        @Override
        public byte[] array() {
            return bytes;
        }

        @Override
        public int offset() {
            return 0;
        }

        /** {@code EX-04}: array-backed and contiguous — the word-at-a-time router path applies. */
        @Override
        public boolean supportsLong() {
            return true;
        }

        @Override
        public long longAt(int index) {
            return longAtLittleEndian(bytes, index);
        }
    }
}
