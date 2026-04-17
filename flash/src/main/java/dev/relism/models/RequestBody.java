package dev.relism.models;

import java.io.*;

/**
 * Accessor for the HTTP request body. Supports two mutually exclusive read modes per request:
 *
 * <ul>
 *   <li>{@link #bytes()} — materialises the full body into a {@code byte[]} and caches it.
 *       Safe to call multiple times; the second call returns the cached array. Throws for
 *       bodies larger than 2 GB.</li>
 *   <li>{@link #stream()} — returns a bounded {@link InputStream} without upfront allocation.
 *       For fixed-length bodies this is a view into the already-buffered header bytes stitched
 *       to the socket; for chunked bodies it is the raw {@link dev.relism.ChunkedInputStream}
 *       that de-chunks on the fly.</li>
 * </ul>
 *
 * <p><b>Mutual exclusivity:</b> calling both {@code bytes()} and {@code stream()} on the same
 * request produces undefined results. Choose one mode per handler.
 *
 * <p><b>Keep-alive:</b> unread body bytes are discarded by {@link Request#drain()} after the
 * handler returns so the socket is correctly positioned for the next pipelined request.
 */
public final class RequestBody {
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final InputStream socket;
    private final long        contentLength;
    private final byte[]      preBuf;
    private final int         preBufOff;
    private final int         preBufLen;

    private byte[] resolved;
    private long   socketConsumed;

    RequestBody(InputStream socket, long contentLength, byte[] preBuf, int preBufOff, int preBufLen) {
        this.socket        = socket;
        this.contentLength = contentLength;
        this.preBuf        = preBuf;
        this.preBufOff     = preBufOff;
        this.preBufLen     = preBufLen;
    }

    /** Pre-resolved body: test payloads and empty body — skips all I/O. */
    private RequestBody(byte[] preResolved) {
        this(null, preResolved.length, null, 0, 0);
        this.resolved = preResolved;
    }

    private static final RequestBody EMPTY = new RequestBody(EMPTY_BYTES);

    static RequestBody of(byte[] bytes) { return new RequestBody(bytes); }
    static RequestBody empty()          { return EMPTY; }

    /** {@code true} if the body has zero bytes ({@code Content-Length: 0} or no body). */
    public boolean isEmpty() { return contentLength == 0; }

    /**
     * Declared body size in bytes. Returns {@code -1} for {@code Transfer-Encoding: chunked}
     * bodies where the size is not known upfront.
     */
    public long contentLength() { return contentLength; }

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
     * <p>For fixed-length bodies: a {@link SequenceInputStream} of any already-buffered header
     * bytes followed by a bounded view of the socket stream — zero heap beyond those small
     * pre-buffered bytes.
     *
     * <p>For chunked bodies: the raw {@link dev.relism.ChunkedInputStream} that de-chunks on
     * the fly; EOF signals the end of the logical body and leaves the socket positioned for
     * the next keep-alive request.
     *
     * <p>If {@link #bytes()} was called first, returns a fresh {@link java.io.ByteArrayInputStream}
     * over the cached array.
     */
    public InputStream stream() {
        if (resolved != null) return new ByteArrayInputStream(resolved);
        if (contentLength < 0) return socket; // ChunkedInputStream — EOF signals end of body
        int  fromBuf    = (int) Math.min(preBufLen, contentLength);
        long fromSocket = contentLength - fromBuf;
        InputStream bufPart = new ByteArrayInputStream(preBuf, preBufOff, fromBuf);
        return fromSocket == 0 ? bufPart : new SequenceInputStream(bufPart, bounded(socket, fromSocket));
    }

    /** Discards unread body bytes to reposition the socket for the next keep-alive request. */
    void drain() {
        if (isEmpty() || resolved != null) return;
        if (contentLength < 0) {
            try { socket.transferTo(OutputStream.nullOutputStream()); } catch (IOException ignored) {}
            return;
        }
        long remaining = (contentLength - preBufLen) - socketConsumed;
        if (remaining > 0) try { socket.skipNBytes(remaining); } catch (IOException ignored) {}
    }

    private InputStream bounded(InputStream src, long limit) {
        return new InputStream() {
            private long left = limit;

            @Override public int read() throws IOException {
                if (left == 0) return -1;
                int b = src.read();
                if (b >= 0) { left--; socketConsumed++; }
                return b;
            }

            @Override public int read(byte[] buf, int off, int len) throws IOException {
                if (left == 0) return -1;
                int n = src.read(buf, off, (int) Math.min(len, left));
                if (n > 0) { left -= n; socketConsumed += n; }
                return n;
            }
        };
    }
}
