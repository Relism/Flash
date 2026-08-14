package dev.relism.flash.bench;

import java.util.Arrays;
import java.util.List;

/** Merges every worker's samples and reduces them to one {@link LoadResult}. */
final class Stats {

  private Stats() {}

  static LoadResult summarize(
      String scenarioLabel, List<LatencyRecorder> perWorker, long errors, long elapsedNanos) {
    int total = 0;
    for (LatencyRecorder recorder : perWorker) total += recorder.count();

    long[] merged = new long[total];
    int offset = 0;
    for (LatencyRecorder recorder : perWorker) {
      long[] samples = recorder.toArray();
      System.arraycopy(samples, 0, merged, offset, samples.length);
      offset += samples.length;
    }
    Arrays.sort(merged);

    return new LoadResult(
        scenarioLabel,
        merged.length,
        errors,
        elapsedNanos / 1_000_000_000.0,
        microsOf(mean(merged)),
        microsOf(percentile(merged, 0.50)),
        microsOf(percentile(merged, 0.99)),
        microsOf(percentile(merged, 0.999)));
  }

  private static double mean(long[] sorted) {
    if (sorted.length == 0) return 0;
    long sum = 0;
    for (long value : sorted) sum += value;
    return (double) sum / sorted.length;
  }

  private static long percentile(long[] sorted, double fraction) {
    if (sorted.length == 0) return 0;
    int index = (int) Math.min(sorted.length - 1, Math.floor(fraction * sorted.length));
    return sorted[index];
  }

  private static double microsOf(double nanos) {
    return nanos / 1000.0;
  }
}
