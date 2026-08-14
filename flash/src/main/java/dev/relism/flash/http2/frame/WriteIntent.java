package dev.relism.flash.http2.frame;

/**
 * "Serialize yourself, then hand me the finished bytes." The interface a stream (and, eventually,
 * connection-level singletons — the precompiled SETTINGS ACK, PING ACK, GOAWAY, WINDOW_UPDATE
 * frames) implements to write through {@link Http2FrameWriter}.
 *
 * <h3>Layer 1 — serialize outside the lock</h3>
 *
 * By the time {@link Http2FrameWriter#write} is called, the implementation has already built its
 * complete output (frame header + HPACK block + payload, or whatever the frame needs) into a buffer
 * it owns — a per-stream scratch buffer, reused across writes, never allocated per call. {@link
 * #buffer()}/{@link #offset()}/{@link #length()} just describe where that already-finished output
 * lives. {@code Http2FrameWriter} never serializes anything itself; it only ever issues one bulk
 * {@code write(buffer, offset, length)} while holding the connection's write lock — see {@code
 * WRITER.md} for why that distinction is the entire point of this design (the lock must never be
 * held across serialization work, only across the syscall).
 *
 * <h3>Intrusive queue linkage</h3>
 *
 * {@link #mpscNext()}/{@link #setMpscNext} are not part of the writer's public contract — they
 * exist so a {@code WriteIntent} can double as an {@link IntrusiveMpscQueue} node with zero extra
 * allocation when the writer is contended. Implementations provide simple field storage; nothing
 * about the field is meaningful outside {@link IntrusiveMpscQueue}.
 */
public interface WriteIntent {

  /** The buffer holding this intent's already-serialized bytes. */
  byte[] buffer();

  /** Offset of the first byte to write, within {@link #buffer()}. */
  int offset();

  /** Number of bytes to write, starting at {@link #offset()}. */
  int length();

  /** Intrusive MPSC queue linkage — see {@link IntrusiveMpscQueue}. Not for external use. */
  WriteIntent mpscNext();

  /** Intrusive MPSC queue linkage — see {@link IntrusiveMpscQueue}. Not for external use. */
  void setMpscNext(WriteIntent next);

  /**
   * Called exactly once after this intent leaves the writer, whether the socket write succeeded or
   * failed. Pooled control-frame intents use this hook to return their slot to the owning
   * connection without allocating a completion object.
   */
  default void completed() {}
}
