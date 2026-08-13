package dev.relism.flash.transport;

import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;

/**
 * The single buffered view over one connection's inbound bytes, for the whole lifetime of the
 * connection. Fixes {@code EX-10} (one syscall per byte in {@code ChunkedInputStream}) and
 * gives {@link dev.relism.flash.transport.ProtocolNegotiator} a way to inspect the first bytes
 * of a plaintext connection (the h2c preface) without consuming them.
 *
 * <h3>Why this exists instead of {@link java.io.BufferedInputStream}</h3>
 * A generic buffered stream would already fix the per-byte-syscall problem, but it cannot
 * "un-consume" bytes without a fragile {@code mark()}/{@code reset()} dance, and it has no way
 * to bound an individual read by an absolute wall-clock deadline (see below). This class is
 * purpose-built for exactly the two things this connection loop needs beyond plain buffering:
 * {@link #peek(byte[], int, int)} (look-ahead without consuming — used once, at connection
 * start, for h2c prior-knowledge detection) and {@link #prependOnce(byte[], int, int)}
 * (zero-allocation, zero-copy re-insertion of bytes the caller already read into its own
 * buffer — used by {@code ChunkedInputStream} to hand back the header-parser's read-ahead
 * bytes instead of the {@code SequenceInputStream}/{@code ByteArrayInputStream} wrapping this
 * replaces).
 *
 * <h3>Deadline, not {@code SO_TIMEOUT} alone</h3>
 * {@link Socket#setSoTimeout(int)} bounds a single {@code read()} call, not a sequence of them —
 * a peer that trickles one byte every 9 seconds never trips a 10-second {@code SO_TIMEOUT}, since
 * each individual read succeeds within the window. {@link #setDeadline(long)} instead records an
 * absolute {@link System#nanoTime()} deadline; every underlying socket read computes the
 * remaining budget and hands exactly that to {@code setSoTimeout} before reading, so a
 * {@link SocketTimeoutException} from an underlying read unambiguously means the deadline —
 * not just one read — has been exceeded. This is what {@code EX-07} requires: "implement that
 * deadline, do not rely on {@code setSoTimeout} alone."
 *
 * <h3>Thread-safety</h3>
 * Not thread-safe, by design — exactly one virtual thread ever owns a connection's inbound
 * bytes at a time (the same invariant {@code RequestParser} and {@code ChunkedInputStream}
 * already assume).
 */
public final class BufferedByteSource extends InputStream {

    /**
     * Default internal buffer size. Matches the relay-buffer convention already used elsewhere
     * in this codebase (the 8 KB {@code STREAM_RELAY_BUFFER} in {@code HttpServer}) rather than
     * introducing a new tuning constant nothing has calibrated yet.
     */
    public static final int DEFAULT_BUFFER_SIZE = 8192;

    private final InputStream in;
    private final Socket socket;
    private final byte[] buf;
    private int pos;
    private int limit;

    // One-shot prepend window (prependOnce) — consumed before buf and before any underlying
    // read. References the caller's own array; never copies it.
    private byte[] prefixBuf;
    private int prefixPos;
    private int prefixLen;

    private boolean deadlineActive;
    private long deadlineNanos;

    public BufferedByteSource(InputStream in, Socket socket) {
        this(in, socket, DEFAULT_BUFFER_SIZE);
    }

    public BufferedByteSource(InputStream in, Socket socket, int bufferSize) {
        this.in = in;
        this.socket = socket;
        this.buf = new byte[bufferSize];
    }

    // ── Deadline ─────────────────────────────────────────────────────────────

    /**
     * Every underlying socket read performed after this call is bounded so that it cannot
     * still be blocking past {@code deadlineNanoTime} (an absolute value comparable to
     * {@link System#nanoTime()}). A read that would exceed the deadline throws
     * {@link SocketTimeoutException} instead of blocking further. Bytes already sitting in the
     * internal buffer or the prepend window are served immediately regardless of the deadline —
     * only reads that would otherwise block on the network are bounded.
     */
    public void setDeadline(long deadlineNanoTime) {
        this.deadlineActive = true;
        this.deadlineNanos = deadlineNanoTime;
    }

    /**
     * Removes the deadline and restores the socket to blocking indefinitely
     * ({@code SO_TIMEOUT = 0}). Must be called before any read the caller wants to be
     * unbounded (e.g. handing the connection off to a long-lived WebSocket session loop).
     */
    public void clearDeadline() throws IOException {
        this.deadlineActive = false;
        socket.setSoTimeout(0);
    }

    // ── InputStream ──────────────────────────────────────────────────────────

    @Override
    public int read() throws IOException {
        if (prefixLen > 0) {
            prefixLen--;
            return prefixBuf[prefixPos++] & 0xFF;
        }
        if (pos >= limit) {
            int n = fillFromUnderlying(buf, 0, buf.length);
            if (n <= 0) return -1;
            pos = 0;
            limit = n;
        }
        return buf[pos++] & 0xFF;
    }

    @Override
    public int read(byte[] dst, int off, int len) throws IOException {
        if (len == 0) return 0;
        if (prefixLen > 0) {
            int n = Math.min(len, prefixLen);
            System.arraycopy(prefixBuf, prefixPos, dst, off, n);
            prefixPos += n;
            prefixLen -= n;
            return n;
        }
        if (pos < limit) {
            int n = Math.min(len, limit - pos);
            System.arraycopy(buf, pos, dst, off, n);
            pos += n;
            return n;
        }
        // Buffer empty. A large request (this is the path RequestParser's own bulk
        // header-buffer fill takes) bypasses the internal buffer entirely — copying it through
        // `buf` first would cost a full extra memcpy for no benefit, since the caller's own
        // array is at least as large as what we would have buffered.
        if (len >= buf.length) {
            return fillFromUnderlying(dst, off, len);
        }
        int n = fillFromUnderlying(buf, 0, buf.length);
        if (n <= 0) return n;
        pos = 0;
        limit = n;
        int c = Math.min(len, limit);
        System.arraycopy(buf, 0, dst, off, c);
        pos = c;
        return c;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 0) return 0;
        long remaining = n;
        if (prefixLen > 0) {
            int s = (int) Math.min(remaining, prefixLen);
            prefixPos += s;
            prefixLen -= s;
            remaining -= s;
        }
        if (remaining > 0 && pos < limit) {
            int s = (int) Math.min(remaining, limit - pos);
            pos += s;
            remaining -= s;
        }
        if (remaining > 0) {
            remaining -= Math.max(0, in.skip(remaining));
        }
        return n - remaining;
    }

    @Override
    public int available() {
        return prefixLen + (limit - pos);
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    // ── Peek and prepend — the two operations beyond InputStream's contract ────

    /**
     * Ensures up to {@code len} bytes are buffered and copies them into {@code dst} <b>without
     * advancing the read position</b> — a subsequent {@code read()} still returns the same
     * bytes. Blocks (bounded by the active deadline, if any) until {@code len} bytes are
     * available or the underlying stream reaches EOF. Returns the number of bytes actually made
     * available, which is less than {@code len} only at EOF.
     *
     * <p>Only valid before anything has been {@link #prependOnce prepended} — in practice this
     * means it is only ever called once, by {@code ProtocolNegotiator}, at the very start of a
     * connection before any other read.
     *
     * @throws IllegalArgumentException if {@code len} exceeds the internal buffer's capacity —
     *         this class cannot peek further ahead than it buffers.
     */
    public int peek(byte[] dst, int off, int len) throws IOException {
        if (len > buf.length) {
            throw new IllegalArgumentException(
                    "peek length " + len + " exceeds buffer capacity " + buf.length);
        }
        if (prefixLen > 0) {
            throw new IllegalStateException(
                    "peek() is only valid before any bytes have been prepended to this source");
        }
        while (limit - pos < len) {
            if (pos > 0) {
                System.arraycopy(buf, pos, buf, 0, limit - pos);
                limit -= pos;
                pos = 0;
            }
            int n = fillFromUnderlying(buf, limit, buf.length - limit);
            if (n <= 0) break;
            limit += n;
        }
        int available = Math.min(len, limit - pos);
        System.arraycopy(buf, pos, dst, off, available);
        return available;
    }

    /**
     * Queues {@code len} bytes, starting at {@code off} in the caller-owned array {@code src},
     * to be served by the next reads <b>before</b> anything else — zero allocation and zero
     * copy, since {@code src} is referenced directly, not duplicated. The caller must not
     * mutate {@code src[off..off+len)} until the prefix is fully consumed.
     *
     * <p>Exactly one prefix may be pending at a time. This is intentional: it exists solely to
     * hand {@code RequestParser}'s header-buffer read-ahead bytes to a fresh
     * {@code ChunkedInputStream} at the start of a chunked body, a single well-defined moment
     * per request — it is not a general-purpose pushback stack.
     *
     * @throws IllegalStateException if a prefix is already pending
     */
    public void prependOnce(byte[] src, int off, int len) {
        if (prefixLen > 0) {
            throw new IllegalStateException("a prefix is already pending on this source");
        }
        this.prefixBuf = src;
        this.prefixPos = off;
        this.prefixLen = len;
    }

    // ── Internal fill ────────────────────────────────────────────────────────

    /**
     * The only place this class ever touches the underlying socket stream. When a deadline is
     * active, computes the exact remaining budget and hands it to {@link Socket#setSoTimeout}
     * before reading, so a {@link SocketTimeoutException} from {@code in.read} unambiguously
     * means the deadline — not merely one read — has elapsed; see the class Javadoc.
     */
    private int fillFromUnderlying(byte[] dst, int off, int len) throws IOException {
        if (!deadlineActive) {
            return in.read(dst, off, len);
        }
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("Read deadline exceeded");
        }
        long remainingMillis = (remainingNanos + 999_999L) / 1_000_000L; // round up
        int timeoutMs = (int) Math.max(1, Math.min(Integer.MAX_VALUE, remainingMillis));
        socket.setSoTimeout(timeoutMs);
        return in.read(dst, off, len);
    }
}
