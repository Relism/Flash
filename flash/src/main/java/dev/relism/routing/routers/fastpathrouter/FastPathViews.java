package dev.relism.routing.routers.fastpathrouter;

import dev.relism.fpr.core.ByteView;
import lombok.NoArgsConstructor;

import java.nio.charset.StandardCharsets;

/** {@link dev.relism.fpr.core.ByteView} implementations used on the router and parser hot paths. */
@NoArgsConstructor
public final class FastPathViews {

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
        public String toString() {
            return new String(buffer, start, length, StandardCharsets.UTF_8);
        }
    }

    /** Mutable composite view: method bytes + path. Reused via ThreadLocal, call reset() before use. */
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
