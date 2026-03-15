package dev.relism.models;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Deferred request body reader.
 *
 * <p>On the first call to {@link #get()}, the body is materialized: any bytes already
 * buffered from the 8 KB header read-ahead are copied first, then the remainder is pulled
 * from the socket stream. The result is cached so subsequent calls are free.
 *
 * <p>When {@code contentLength} is zero the instance is pre-resolved to an empty array
 * and no stream access ever occurs.
 */
final class LazyBody {
    private static final byte[] EMPTY = new byte[0];

    private final InputStream stream;
    private final int         contentLength;
    private final byte[]      preBuf;
    private final int         preBufOff;
    private final int         preBufLen;
    private       byte[]      resolved;

    LazyBody(InputStream stream, int contentLength, byte[] preBuf, int preBufOff, int preBufLen) {
        this.stream        = stream;
        this.contentLength = contentLength;
        this.preBuf        = preBuf;
        this.preBufOff     = preBufOff;
        this.preBufLen     = preBufLen;
    }

    /** Returns a pre-resolved {@code LazyBody} backed by an already-materialized byte array. */
    static LazyBody of(byte[] bytes) {
        LazyBody lb = new LazyBody(null, bytes.length, null, 0, 0);
        lb.resolved = bytes;
        return lb;
    }

    /** Returns a pre-resolved empty {@code LazyBody}. */
    static LazyBody empty() {
        LazyBody lb = new LazyBody(null, 0, null, 0, 0);
        lb.resolved = EMPTY;
        return lb;
    }

    byte[] get() {
        if (resolved != null) return resolved;
        byte[] buf    = new byte[contentLength];
        int    copied = Math.min(preBufLen, contentLength);
        if (copied > 0) System.arraycopy(preBuf, preBufOff, buf, 0, copied);
        if (copied < contentLength) {
            try {
                stream.readNBytes(buf, copied, contentLength - copied);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return resolved = buf;
    }
}
