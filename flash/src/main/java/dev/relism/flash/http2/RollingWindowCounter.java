package dev.relism.flash.http2;

/** Allocation-free two-bucket rolling rate counter owned by one connection thread. */
final class RollingWindowCounter {
  private final long bucketNanos;
  private long currentBucket;
  private int currentCount;
  private int previousCount;

  RollingWindowCounter(long intervalMillis) {
    if (intervalMillis < 2) throw new IllegalArgumentException("interval must be at least 2 ms");
    bucketNanos = intervalMillis * 1_000_000L / 2;
  }

  boolean incrementExceeded(int limit) {
    return incrementExceeded(limit, System.nanoTime());
  }

  boolean incrementExceeded(int limit, long nowNanos) {
    long bucket = nowNanos / bucketNanos;
    if (currentBucket == 0) {
      currentBucket = bucket;
    } else if (bucket != currentBucket) {
      previousCount = bucket == currentBucket + 1 ? currentCount : 0;
      currentCount = 0;
      currentBucket = bucket;
    }
    currentCount++;
    return currentCount + previousCount > limit;
  }
}
