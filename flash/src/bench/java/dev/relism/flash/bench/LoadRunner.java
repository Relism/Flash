package dev.relism.flash.bench;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.LongAdder;

/**
 * Drives a fixed number of concurrent virtual-thread workers against one {@link WorkerFactory},
 * each worker looping its own {@link WorkUnit#request()} until a wall-clock deadline. A discarded
 * warmup phase runs first so JIT warmup and connection setup don't skew the measured phase.
 */
final class LoadRunner {

  private LoadRunner() {}

  static LoadResult execute(
      String scenarioLabel,
      int concurrency,
      Duration warmup,
      Duration measurement,
      WorkerFactory factory)
      throws InterruptedException {
    runUntil(concurrency, System.nanoTime() + warmup.toNanos(), factory, null, null);

    LongAdder errors = new LongAdder();
    List<LatencyRecorder> perWorker = new ArrayList<>(concurrency);
    for (int i = 0; i < concurrency; i++) perWorker.add(new LatencyRecorder());

    long measureStart = System.nanoTime();
    runUntil(concurrency, measureStart + measurement.toNanos(), factory, errors, perWorker);

    return Stats.summarize(scenarioLabel, perWorker, errors.sum(), System.nanoTime() - measureStart);
  }

  private static void runUntil(
      int concurrency,
      long deadlineNanos,
      WorkerFactory factory,
      LongAdder errors,
      List<LatencyRecorder> perWorker)
      throws InterruptedException {
    try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int i = 0; i < concurrency; i++) {
        LatencyRecorder recorder = perWorker == null ? null : perWorker.get(i);
        pool.execute(() -> worker(deadlineNanos, factory, errors, recorder));
      }
    }
  }

  private static void worker(
      long deadlineNanos, WorkerFactory factory, LongAdder errors, LatencyRecorder recorder) {
    try (WorkUnit unit = factory.create()) {
      while (System.nanoTime() < deadlineNanos) {
        long start = System.nanoTime();
        try {
          unit.request();
          if (recorder != null) recorder.record(System.nanoTime() - start);
        } catch (Exception requestFailure) {
          if (errors != null) errors.increment();
        }
      }
    } catch (Exception setupFailure) {
      if (errors != null) errors.increment();
    }
  }
}
