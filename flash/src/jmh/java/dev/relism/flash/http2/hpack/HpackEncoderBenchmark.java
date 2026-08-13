package dev.relism.flash.http2.hpack;

import dev.relism.flash.bytes.ByteWriter;
import java.nio.charset.StandardCharsets;
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

/** Measures a representative stateless response header block. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class HpackEncoderBenchmark {
  private static final byte[] CONTENT_LENGTH = "1024".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CONTENT_TYPE = "application/json".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] CACHE_CONTROL = "no-cache".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ETAG_NAME = "etag".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] ETAG = "\"abc123\"".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] SERVER = "Flash".getBytes(StandardCharsets.US_ASCII);
  private final ByteWriter output = new ByteWriter(128);

  @Benchmark
  public int encodeTypicalResponse() {
    output.reset();
    HpackEncoder.writeIndexed(output, 8);
    HpackEncoder.writeLiteralWithNameIndex(output, 31, CONTENT_TYPE, true);
    HpackEncoder.writeLiteralWithNameIndex(output, 28, CONTENT_LENGTH, false);
    HpackEncoder.writeLiteralWithNameIndex(output, 24, CACHE_CONTROL, true);
    HpackEncoder.writeLiteralWithNameIndex(output, 51, SERVER, true);
    HpackEncoder.writeLiteral(output, ETAG_NAME, ETAG);
    return output.length();
  }
}
