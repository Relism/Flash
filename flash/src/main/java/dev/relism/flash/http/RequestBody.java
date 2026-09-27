package dev.relism.flash.http;

import java.io.*;

/**
 * Accessor for the HTTP request body. Supports two mutually exclusive read modes per request:
 *
 * <ul>
 *   <li>{@link #bytes()} — materialises the full body into a {@code byte[]} and caches it.
 *       Safe to call multiple times; the second call returns the cached array. Throws for
 *       bodies larger than 2 GB.</li>
 *   <li>{@link #stream()} — returns a bounded {@link InputStream} without upfront allocation.
 *       into the already-buffered header bytes stitched to the socket; for chunked bodies it is
 *       the raw {@link dev.relism.flash.ChunkedInputStream} that de-chunks on the fly.</li>
 * </ul>
 *
 * <p><b>Mutual exclusivity:</b> calling both {@code bytes()} and {@code stream()} on the same
 * request produces undefined results. Choose one mode per handler.
 *
 * <p><b>Keep-alive:</b> unread body bytes are discarded by {@link Request#drain()} after the
 * handler returns so the socket is correctly positioned for the next pipelined request.
 *
 * One instance per connection (owned by {@code RequestParser}, repositioned via {@link #reset}
 * for every request), the same treatment {@link Request}/{@link RequestLine} get. The {@link
 * #of(byte[])} factory below remains for test/manual construction and returns a freestanding,
 * unpooled instance — exactly like {@link Request}'s own manual constructor.
 *
 * {@link #stream()} used to allocate a {@link SequenceInputStream}, a {@link ByteArrayInputStream}
 * and an anonymous bounded {@link InputStream} on every call. It now hands out one persistent
 * {@link BoundedBufferedInputStream}, repositioned per request instead of reallocated.
 * {@link #drain()}'s chunked-body path used to call {@code InputStream.transferTo}, which
 * allocates a fresh 8 KiB {@code byte[]} internally on every call (the JDK default
 * implementation); it now drains through a lazily-created, persistent buffer instead.
 */
public class RequestBody {
    private InputStream socket;
    private long        contentLength;
    private byte[]      preBuf;
    private int         preBufOff;
    private int         preBufLen;

    private byte[] resolved;
    private long   socketConsumed;

    private BoundedBufferedInputStream boundedStream;

    private byte[] drainBuffer;

    /** Pooled instance, populated later via {@link #reset}. One per connection — see {@code RequestParser}. */
    public RequestBody() {
    }

    RequestBody(InputStream socket, long contentLength, byte[] preBuf, int preBufOff, int preBufLen) {
        reset(socket, contentLength, preBuf, preBufOff, preBufLen);
    }

    /** Pre-resolved body: test payloads and empty body — skips all I/O. Always a freestanding, unpooled instance. */
    private RequestBody(byte[] preResolved) {
        reset(null, preResolved.length, null, 0, 0);
        this.resolved = preResolved;
    }

    /**
     * Repositions this instance over a new request. {@code public} because {@code RequestParser}
     * (a different package) owns and resets its own pooled instance directly — matching
     * {@link RequestLine#reset}'s precedent — not because user code should ever call it.
     */
    public void reset(InputStream socket, long contentLength, byte[] preBuf, int preBufOff, int preBufLen) {
        this.socket        = socket;
        this.contentLength = contentLength;
        this.preBuf        = preBuf;
        this.preBufOff     = preBufOff;
        this.preBufLen     = preBufLen;
        this.resolved       = null;
        this.socketConsumed = 0;
    }

    static RequestBody of(byte[] bytes) { return new RequestBody(bytes); }
    static RequestBody empty()          { return new RequestBody(new byte[0]); }

    /** {@code true} if the body has zero bytes ({@code Content-Length: 0} or no body). */
    public boolean isEmpty() { return contentLength == 0; }

    /**
     * Declared body size in bytes. Returns {@code -1} for {@code Transfer-Encoding: chunked}
     * bodies where the size is not known upfront.
     */
    public long contentLength() { return contentLength; }

    /** Whether the complete body has been consumed by the application. */
    public boolean fullyRead() {
        if (resolved != null || contentLength == 0) return true;
        if (socket instanceof BodyCompletion completion) return completion.fullyRead();
        if (contentLength < 0) return false;
        if (boundedStream != null) return boundedStream.complete();
        return preBufLen >= contentLength;
    }

    /**
     * Materialises and caches the full body. Suitable for JSON, small form data, and any payload
     * that must be inspected in full. The result is cached — repeated calls return the same array.
     *
     * <p>For chunked bodies ({@link #contentLength()} {@code == -1}), reads until the chunked
     * stream signals EOF.
     *
     * @throws IllegalStateException if {@link #contentLength()} exceeds {@code Integer.MAX_VALUE}
     *                               (~2 GB); use {@link #stream()} for large bodies instead
     */
    public byte[] bytes() {
        if (resolved != null) return resolved;
        if (contentLength < 0) { // chunked — read until ChunkedInputStream signals EOF
            try { return resolved = socket.readAllBytes(); } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        if (contentLength > Integer.MAX_VALUE)
            throw new IllegalStateException("Body too large to materialize (" + contentLength + " bytes), use stream()");
        int size   = (int) contentLength;
        int copied = (int) Math.min(preBufLen, contentLength);
        byte[] buf = new byte[size];
        if (copied > 0) System.arraycopy(preBuf, preBufOff, buf, 0, copied);
        if (copied < size) {
            try {
                socket.readNBytes(buf, copied, size - copied);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            socketConsumed = contentLength - preBufLen;
        }
        return resolved = buf;
    }

    /**
     * Returns a bounded {@link InputStream} over the body without upfront allocation.
     *
     * <p>For fixed-length bodies: a reused {@link BoundedBufferedInputStream} (see the class
     * view of the socket stream — zero allocation on a warm connection.
     *
     * <p>For chunked bodies: the raw {@link dev.relism.flash.ChunkedInputStream} that de-chunks on
     * the fly; EOF signals the end of the logical body and leaves the socket positioned for
     * the next keep-alive request.
     *
     * <p>If {@link #bytes()} was called first, returns a fresh {@link java.io.ByteArrayInputStream}
     */
    public InputStream stream() {
        if (resolved != null) return new ByteArrayInputStream(resolved);
        if (contentLength < 0) return socket; // ChunkedInputStream — EOF signals end of body
        if (boundedStream == null) boundedStream = new BoundedBufferedInputStream();
        int  fromBuf    = (int) Math.min(preBufLen, contentLength);
        long fromSocket = contentLength - fromBuf;
        boundedStream.reset(preBuf, preBufOff, fromBuf, fromSocket);
        return boundedStream;
    }

    /** Discards unread body bytes to reposition the socket for the next keep-alive request. */
    void drain() {
        if (isEmpty() || resolved != null) return;
        if (contentLength < 0) {
            // byte[] on every call — replaced with a buffer this instance allocates once
            // (lazily, only if a chunked body is ever actually drained) and reuses thereafter.
            if (drainBuffer == null) drainBuffer = new byte[8192];
            try {
                while (socket.read(drainBuffer) > 0) { /* discard */ }
            } catch (IOException ignored) {
            }
            return;
        }
        long remaining = (contentLength - preBufLen) - socketConsumed;
        if (remaining > 0) try { socket.skipNBytes(remaining); } catch (IOException ignored) {}
    }

    /**
     * A body stream that reads first from the
     * caller-owned pre-buffered array, then from the socket, bounded overall to a fixed length —
     * replacing the {@code SequenceInputStream}+{@code ByteArrayInputStream}+anonymous-bounded-
     * stream trio that used to be allocated fresh on every {@link #stream()} call. One instance
     * lives on the owning {@link RequestBody} for the whole connection; {@link #reset} repositions
     * it for each new request.
     */
    private final class BoundedBufferedInputStream extends InputStream {
        private byte[] preBuf;
        private int    preBufPos;
        private int    preBufRemaining;
        private long   socketRemaining;

        void reset(byte[] preBuf, int preBufOff, int preBufLen, long socketRemaining) {
            this.preBuf          = preBuf;
            this.preBufPos       = preBufOff;
            this.preBufRemaining = preBufLen;
            this.socketRemaining = socketRemaining;
        }

        boolean complete() {
            return preBufRemaining == 0 && socketRemaining == 0;
        }

        @Override
        public int read() throws IOException {
            if (preBufRemaining > 0) {
                preBufRemaining--;
                return preBuf[preBufPos++] & 0xFF;
            }
            if (socketRemaining == 0) return -1;
            int b = socket.read();
            if (b >= 0) { socketRemaining--; socketConsumed++; }
            return b;
        }

        @Override
        public int read(byte[] dst, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (preBufRemaining > 0) {
                int n = Math.min(len, preBufRemaining);
                System.arraycopy(preBuf, preBufPos, dst, off, n);
                preBufPos += n;
                preBufRemaining -= n;
                return n;
            }
            if (socketRemaining == 0) return -1;
            int n = socket.read(dst, off, (int) Math.min(len, socketRemaining));
            if (n > 0) { socketRemaining -= n; socketConsumed += n; }
            return n;
        }
    }
}
