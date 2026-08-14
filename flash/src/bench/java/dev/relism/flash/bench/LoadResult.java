package dev.relism.flash.bench;

/** One scenario's outcome: throughput and latency distribution over the measured phase only. */
record LoadResult(
    String scenario,
    long requests,
    long errors,
    double seconds,
    double meanLatencyMicros,
    double p50Micros,
    double p99Micros,
    double p999Micros) {

  double requestsPerSecond() {
    return seconds == 0 ? 0 : requests / seconds;
  }
}
