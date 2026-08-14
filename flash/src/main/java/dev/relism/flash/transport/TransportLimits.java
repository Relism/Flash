package dev.relism.flash.transport;

/**
 * Transport-level bound on concurrent connections, enforced by {@link ConnectionRunner} before
 * any per-connection state (TLS handshake, protocol negotiation, HPACK tables, buffers) is set
 * up. Unlike {@link dev.relism.flash.http2.Http2Limits} (bounds on what one already-admitted
 * connection may do), this bounds how many connections are admitted at all — the guard a stress
 * test found completely absent: {@code AcceptLoop} accepted unconditionally, so a connection
 * flood ran the JVM out of heap rather than being turned away.
 */
public final class TransportLimits {

  private TransportLimits() {}

  /**
   * Deliberately conservative estimate of one connection's worst-case retained heap (HPACK
   * tables, stream table, in-flight response batches, up to {@code
   * Http2Limits#MAX_CONCURRENT_STREAMS} concurrent streams), used only to size {@link
   * #defaultMaxConnections()}'s auto-scaled budget — not an enforced per-connection cap.
   *
   * <p>Not a precise per-byte accounting. A stress test on this codebase (h2load, 20 concurrent
   * HTTP/2 streams per connection) observed {@code OutOfMemoryError} somewhere between 200 and
   * 400 concurrent connections on a 1.5 GiB heap. This constant is chosen so {@link
   * #defaultMaxConnections()} lands comfortably below that observed floor (~150 connections at
   * 1.5 GiB) rather than hugging it. A heap-dump-derived precise figure is a natural follow-up;
   * until then this trades some throughput headroom for a real safety margin.
   */
  static final long ASSUMED_WORST_CASE_BYTES_PER_CONNECTION = 5L * 1024 * 1024;

  /**
   * Fraction of the JVM's max heap set aside for connection-admission accounting; the rest is
   * left for GC headroom, response buffers, and everything else the server needs.
   */
  static final double HEAP_FRACTION_FOR_CONNECTIONS = 0.5;

  /** Floor so a tiny heap (dev/test containers) still gets a usable, non-degenerate limit. */
  static final int MIN_MAX_CONNECTIONS = 64;

  /**
   * Auto-scaled default for {@code FlashConfiguration#getMaxConnections()}. Computed from {@link
   * Runtime#maxMemory()} so the same default protects a 256 MiB container and an 8 GiB one
   * without operator input; set {@code maxConnections} explicitly to override it, or to {@code 0}
   * to disable the check (unlimited — the behavior every version before this had unconditionally).
   */
  public static int defaultMaxConnections() {
    long heapBudget = (long) (Runtime.getRuntime().maxMemory() * HEAP_FRACTION_FOR_CONNECTIONS);
    long computed = heapBudget / ASSUMED_WORST_CASE_BYTES_PER_CONNECTION;
    return (int) Math.max(MIN_MAX_CONNECTIONS, Math.min(Integer.MAX_VALUE, computed));
  }
}
