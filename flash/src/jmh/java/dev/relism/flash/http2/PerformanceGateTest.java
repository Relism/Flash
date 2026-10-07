package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collection;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.results.Result;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

/** Short, forked JMH gates used by CI; full publication runs retain each benchmark's annotations. */
@EnabledIfSystemProperty(named = "flash.performance.gates", matches = "true")
class PerformanceGateTest {
  private static final double ALLOCATION_NOISE_FLOOR = 0.05;
  private static final String INCLUDE =
      "(RequestPipelineBenchmark.parseAndRoute"
          + "|Http2StreamBenchmark.lifecycle"
          + "|Http2ResponseWriterBenchmark.encodeResponse"
          + "|HpackDecoderBenchmark.decodeTypicalBrowserRequest"
          + "|HpackEncoderBenchmark.encodeTypicalResponse"
          + "|FrameLayerBenchmark.readValidateAndDiscard)";

  @Test
  void allocationAndLatencyBaselinesHold() throws Exception {
    Collection<RunResult> allocationResults = new Runner(allocationOptions()).run();
    assertFalse(allocationResults.isEmpty(), "JMH did not discover the allocation gates");
    for (RunResult run : allocationResults) {
      String benchmark = shortName(run.getParams().getBenchmark());
      Result<?> allocation = run.getSecondaryResults().get("gc.alloc.rate.norm");
      assertTrue(allocation != null, "missing allocation measurement for " + benchmark);
      assertTrue(
          allocation.getScore() <= ALLOCATION_NOISE_FLOOR,
          () -> benchmark + " allocated " + allocation.getScore() + " B/op");
    }

    Collection<RunResult> latencyResults = new Runner(latencyOptions()).run();
    assertFalse(latencyResults.isEmpty(), "JMH did not discover the latency gates");
    for (RunResult run : latencyResults) {
      String benchmark = shortName(run.getParams().getBenchmark());
      Double maximumNanos = MAXIMUM_P99_NANOS.get(benchmark);
      assertTrue(maximumNanos != null, "missing latency baseline for " + benchmark);
      Result<?> p99 = run.getSecondaryResults().get("p0.99");
      assertTrue(p99 != null, "missing p99 measurement for " + benchmark);
      double score = p99.getScore();
      assertTrue(
          score <= maximumNanos,
          () -> benchmark + " p99 regressed to " + score + " ns/op; gate is " + maximumNanos);
    }
  }

  private static Options allocationOptions() {
    return commonOptions()
        .mode(Mode.AverageTime)
        .addProfiler(GCProfiler.class)
        .build();
  }

  private static Options latencyOptions() {
    return commonOptions().mode(Mode.SampleTime).build();
  }

  private static ChainedOptionsBuilder commonOptions() {
    return new OptionsBuilder()
        .include(INCLUDE)
        .warmupIterations(2)
        .warmupTime(TimeValue.milliseconds(250))
        .measurementIterations(3)
        .measurementTime(TimeValue.milliseconds(350))
        .forks(1)
        .shouldFailOnError(true);
  }

  private static String shortName(String benchmark) {
    return benchmark.substring(benchmark.lastIndexOf('.') + 1);
  }

  // Filled from a controlled baseline run, with 35% CI headroom.
  private static final Map<String, Double> MAXIMUM_P99_NANOS =
      Map.of(
          "parseAndRoute", 45_000.0,
          "lifecycle", 2_900.0,
          "encodeResponse", 1_350.0,
          "decodeTypicalBrowserRequest", 7_100.0,
          "encodeTypicalResponse", 850.0,
          "readValidateAndDiscard", 2_700.0);
}
