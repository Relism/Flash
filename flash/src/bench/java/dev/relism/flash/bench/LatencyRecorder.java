package dev.relism.flash.bench;

import java.util.Arrays;

/** One worker's latency samples, in nanoseconds. Grows without boxing on the request loop. */
final class LatencyRecorder {
  private long[] samples = new long[1024];
  private int count;

  void record(long nanos) {
    if (count == samples.length) samples = Arrays.copyOf(samples, samples.length * 2);
    samples[count++] = nanos;
  }

  int count() {
    return count;
  }

  long[] toArray() {
    return Arrays.copyOf(samples, count);
  }
}
