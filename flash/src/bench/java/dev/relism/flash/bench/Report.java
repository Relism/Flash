package dev.relism.flash.bench;

import java.util.List;

/** Prints results as a fixed-width table on stdout — no file output, this is a manual tool. */
final class Report {

  private Report() {}

  static void print(List<LoadResult> results) {
    System.out.printf(
        "%-16s %10s %8s %12s %10s %10s %10s %10s%n",
        "scenario", "requests", "errors", "req/s", "mean(us)", "p50(us)", "p99(us)", "p999(us)");
    for (LoadResult result : results) {
      System.out.printf(
          "%-16s %10d %8d %12.1f %10.1f %10.1f %10.1f %10.1f%n",
          result.scenario(),
          result.requests(),
          result.errors(),
          result.requestsPerSecond(),
          result.meanLatencyMicros(),
          result.p50Micros(),
          result.p99Micros(),
          result.p999Micros());
    }
  }
}
