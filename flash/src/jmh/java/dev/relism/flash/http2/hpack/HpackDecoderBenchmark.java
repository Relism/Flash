package dev.relism.flash.http2.hpack;

import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Measures steady-state decoding into reusable per-stream storage. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class HpackDecoderBenchmark {
  private final HpackDecoder decoder = new HpackDecoder();
  private final HpackHeaderBlock headers = new HpackHeaderBlock();
  private final byte[] block = {(byte) 0x82, (byte) 0x87, (byte) 0x84, (byte) 0x88};

  @Benchmark
  public int decodeStaticRequest() {
    headers.reset();
    decoder.decode(block, 0, block.length, headers);
    return headers.count();
  }
}
