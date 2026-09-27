package dev.relism.flash.http2.stream;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Measures one response pass across a connection with N simultaneously live request streams. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class Http2MultiplexingBenchmark {
  private static final byte[] BODY = "ok".getBytes(StandardCharsets.US_ASCII);

  @Param({"1", "8", "64", "256"})
  public int liveStreams;

  private Http2Stream[] streams;

  @Setup(Level.Trial)
  public void setup() {
    Http2StreamTable table = new Http2StreamTable(liveStreams);
    streams = new Http2Stream[liveStreams];
    ByteWriter encoded = new ByteWriter(64);
    HpackEncoder.writeIndexed(encoded, 2);
    HpackEncoder.writeIndexed(encoded, 7);
    HpackEncoder.writeLiteralWithNameIndex(
        encoded, 4, "/get".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        encoded, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);
    HpackDecoder decoder = new HpackDecoder();
    for (int i = 0; i < streams.length; i++) {
      Http2Stream stream = table.acquire(i * 2 + 1);
      decoder.decode(encoded.array(), 0, encoded.length(), stream.headerBlock());
      stream.assembleRequest(null, null, null);
      streams[i] = stream;
    }
  }

  @Benchmark
  public int encodeAllLiveStreamResponses() {
    int wireBytes = 0;
    for (Http2Stream stream : streams) {
      stream
          .responseWriter()
          .prepare(
              stream.resetResponse().body(BODY),
              stream.id(),
              false,
              false,
              true,
              false,
              false,
              16_384,
              32_768,
              65_535);
      wireBytes += stream.responseWriter().length();
    }
    return wireBytes;
  }
}
