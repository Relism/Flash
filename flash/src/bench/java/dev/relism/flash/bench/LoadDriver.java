package dev.relism.flash.bench;

import java.net.URI;
import java.time.Duration;

/** Runs one scenario (a protocol at a fixed concurrency) against a live target and returns its stats. */
interface LoadDriver {
  LoadResult run(
      String scenarioLabel, URI target, int concurrency, Duration warmup, Duration measurement)
      throws InterruptedException;
}
