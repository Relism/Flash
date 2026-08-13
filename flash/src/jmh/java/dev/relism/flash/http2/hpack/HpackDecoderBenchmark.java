package dev.relism.flash.http2.hpack;

import java.util.concurrent.TimeUnit;
import dev.relism.flash.bytes.ByteWriter;
import java.nio.charset.StandardCharsets;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Setup;
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
  private byte[] browserBlock;

  @Setup(Level.Trial)
  public void setupTypicalBlock() {
    ByteWriter encoded = new ByteWriter(256);
    HpackEncoder.writeIndexed(encoded, 2);
    HpackEncoder.writeIndexed(encoded, 7);
    HpackEncoder.writeLiteralWithNameIndex(
        encoded, 4, "/products?category=books".getBytes(StandardCharsets.US_ASCII), true);
    HpackEncoder.writeLiteralWithNameIndex(
        encoded, 1, "shop.example.com".getBytes(StandardCharsets.US_ASCII), true);
    HpackEncoder.writeLiteralWithNameIndex(
        encoded, 19, "text/html,application/xhtml+xml".getBytes(StandardCharsets.US_ASCII), true);
    HpackEncoder.writeLiteralWithNameIndex(
        encoded, 16, "gzip, deflate".getBytes(StandardCharsets.US_ASCII), true);
    HpackEncoder.writeLiteralWithNameIndex(
        encoded, 55, "Mozilla/5.0 benchmark".getBytes(StandardCharsets.US_ASCII), true);
    browserBlock = java.util.Arrays.copyOf(encoded.array(), encoded.length());
  }

  @Benchmark
  public int decodeStaticRequest() {
    headers.reset();
    decoder.decode(block, 0, block.length, headers);
    return headers.count();
  }

  @Benchmark
  public int decodeTypicalBrowserRequest() {
    headers.reset();
    decoder.decode(browserBlock, 0, browserBlock.length, headers);
    return headers.count();
  }
}
