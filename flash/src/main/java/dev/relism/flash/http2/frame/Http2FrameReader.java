package dev.relism.flash.http2.frame;

import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.transport.BufferedByteSource;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/**
 * Reads length-prefixed HTTP/2 frames from one connection's {@link BufferedByteSource}. Simpler
 * than {@code RequestParser} by construction: HTTP/2 frames declare their length up front (the
 * 9-byte header), so nothing is ever scanned for — {@code Http2FrameReader} only ever needs to know
 * "do I have N bytes yet", never "where does this end".
 *
 * <h3>Buffer discipline</h3>
 *
 * One growable {@code byte[]} per connection, reused across every frame — the same
 * compact-before-grow discipline {@code RequestParser}'s own buffer uses. A frame's declared length
 * is checked against {@link Http2Limits#MAX_FRAME_SIZE_LOCAL} <em>before</em> the buffer
 * length-check, not after an allocation already paid for it.
 *
 * <h3>Usage</h3>
 *
 * <pre>{@code
 * FrameHeader header = reader.readFrame();
 * if (header == null) { /* clean EOF between frames — connection closing *\/ }
 * // ... process header.buffer()[header.payloadOffset(), +header.length()) ...
 * reader.consumeFrame(); // MUST be called before the next readFrame()
 * }</pre>
 *
 * <h3>Thread-safety</h3>
 *
 * Not thread-safe — exactly one virtual thread (the connection's demux loop) ever calls this, the
 * same invariant every other per-connection reader in this codebase assumes.
 */
public final class Http2FrameReader {
  private static final int FRAME_HEADER_SIZE = 9;
  private static final int INITIAL_BUFFER_SIZE = 16 * 1024;

  private final BufferedByteSource in;
  private final FrameHeader header = new FrameHeader();
  private byte[] buffer;
  private int base; // offset of the first unconsumed byte
  private int totalRead; // count of valid unconsumed bytes at [base, base + totalRead)
  private long frameDeadlineNanos;

  public Http2FrameReader(BufferedByteSource in) {
    this(in, INITIAL_BUFFER_SIZE);
  }

  public Http2FrameReader(BufferedByteSource in, int initialBufferSize) {
    this.in = in;
    this.buffer = new byte[Math.max(initialBufferSize, FRAME_HEADER_SIZE)];
  }

  /**
   * Reads the next frame's header and payload, bounded by {@link
   * Http2Limits#FRAME_READ_TIMEOUT_MS}, and returns the reused {@link FrameHeader} flyweight
   * positioned over it — or {@code null} on a clean EOF between frames (the peer closed the
   * connection while nothing was in flight; not an error).
   *
   * <p>The caller MUST call {@link #consumeFrame()} exactly once after processing this frame (or
   * deciding to discard it) and before calling this method again.
   *
   * @throws Http2Exception if the declared length exceeds {@link Http2Limits#MAX_FRAME_SIZE_LOCAL}
   * @throws EOFException if the connection closes after a frame has already started arriving
   * @throws java.net.SocketTimeoutException if {@link Http2Limits#FRAME_READ_TIMEOUT_MS} elapses
   */
  public FrameHeader readFrame() throws IOException {
    return readFrame(Http2Limits.FRAME_READ_TIMEOUT_MS);
  }

  /** Reads one frame using a caller-supplied upper bound for this frame's absolute deadline. */
  public FrameHeader readFrame(long timeoutMs) throws IOException {
    if (timeoutMs <= 0) throw new IllegalArgumentException("timeoutMs must be positive");
    long now = System.nanoTime();
    if (frameDeadlineNanos == 0) {
      frameDeadlineNanos = now + Http2Limits.FRAME_READ_TIMEOUT_MS * 1_000_000L;
    }
    in.setDeadline(Math.min(frameDeadlineNanos, now + timeoutMs * 1_000_000L));
    try {
      if (!ensureAvailable(FRAME_HEADER_SIZE)) {
        frameDeadlineNanos = 0;
        return null; // clean EOF: nothing buffered yet, peer closed between frames
      }
      int declaredLength = decodeLength(buffer, base);
      // never causes an oversized allocation, only a rejection.
      if (declaredLength > Http2Limits.MAX_FRAME_SIZE_LOCAL) {
        throw Http2Exception.FRAME_SIZE_ERROR;
      }
      ensureAvailable(FRAME_HEADER_SIZE + declaredLength);
      header.reset(buffer, base);
      return header;
    } catch (java.net.SocketTimeoutException timeout) {
      if (totalRead == 0) frameDeadlineNanos = 0;
      throw timeout;
    } finally {
      in.clearDeadline();
    }
  }

  /** Advances past the frame last returned by {@link #readFrame()}. Zero-copy, zero-allocation. */
  public void consumeFrame() {
    int consumed = FRAME_HEADER_SIZE + header.length();
    base += consumed;
    totalRead -= consumed;
    if (totalRead == 0) {
      base = 0; // nothing buffered — reset to the front rather than drifting forever
    }
    frameDeadlineNanos = 0;
  }

  /** Whether a partially received frame exhausted its non-renewable absolute deadline. */
  public boolean frameDeadlineExpired() {
    return totalRead != 0 && System.nanoTime() >= frameDeadlineNanos;
  }

  /** Whether another frame may be consumed immediately without waiting for network input. */
  public boolean hasBufferedInput() {
    return totalRead != 0 || in.available() != 0;
  }

  private static int decodeLength(byte[] buf, int off) {
    int b0 = buf[off] & 0xFF, b1 = buf[off + 1] & 0xFF, b2 = buf[off + 2] & 0xFF;
    return (b0 << 16) | (b1 << 8) | b2;
  }

  /**
   * Ensures at least {@code need} bytes are available starting at {@link #base}, growing or
   * compacting the buffer as necessary. Returns {@code false} only for a clean EOF with nothing at
   * all buffered yet (the between-frames case); an EOF after any bytes of the current frame have
   * already arrived is a genuine truncation and throws.
   */
  private boolean ensureAvailable(int need) throws IOException {
    while (totalRead < need) {
      if (base + need > buffer.length) {
        if (base > 0) {
          // Compact: slide unconsumed bytes to the front — frees room without growing.
          System.arraycopy(buffer, base, buffer, 0, totalRead);
          base = 0;
        } else {
          // need <= 9 + MAX_FRAME_SIZE_LOCAL always, by readFrame()'s own check before
          // the payload-sized call — grow exactly enough, never unbounded.
          int grown = buffer.length;
          while (grown < need) grown *= 2;
          buffer = Arrays.copyOf(buffer, grown);
        }
      }
      int n = in.read(buffer, base + totalRead, buffer.length - base - totalRead);
      if (n < 0) {
        if (totalRead == 0) return false;
        throw new EOFException(
            "connection closed mid-frame (" + totalRead + "/" + need + " bytes read)");
      }
      totalRead += n;
    }
    return true;
  }
}
