package dev.relism.routing.routers.fastpathrouter;

import dev.relism.fpr.core.ByteView;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;

/**
 * Container class for various {@link ByteView} implementations used by the FastPathRouter.
 * Consolidating these views reduces package clutter while maintaining high-performance byte access.
 */
@NoArgsConstructor
public final class FastPathViews {

    /**
     * High-performance, zero-copy ByteView that points to a shared request buffer.
     * Used by the {@link dev.relism.RequestParser} to scan for paths without allocations.
     */
    public static final class RequestByteView implements ByteView {
        private final byte[] buffer;
        private final int start;
        private final int length;

        public RequestByteView(byte[] buffer, int start, int length) {
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
        public String toString() { // CHANGED: decode slice directly; avoids full-buffer String copy
            return new String(buffer, start, length, StandardCharsets.UTF_8);
        }
    }

    /**
     * A composite ByteView that prefixes a method's bytes to a path view.
     * Enables method+path routing in a single pass with no allocation.
     *
     * <p>Mutable by design — reused across requests via {@code ThreadLocal}.
     * Call {@link #reset} before each use.
     */
    public static final class MethodPathByteView implements ByteView {
        private byte[] method;
        private ByteView path;
        private int totalLength;

        /** Binds this view to a new method+path pair. Must be called before each use. */
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

    /**
     * ByteView implementation for raw byte arrays, typically from a socket.
     */
    public static class SocketByteView implements ByteView {
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
    }

    /**
     * ByteView implementation for Java Strings.
     */
    public static class StringByteView implements ByteView {
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
    }
}
