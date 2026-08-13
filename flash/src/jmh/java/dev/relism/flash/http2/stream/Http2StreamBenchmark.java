package dev.relism.flash.http2.stream;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.models.Response;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/** Measures the pooled HPACK-decode, request-assembly and fixed-response stream lifecycle. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class Http2StreamBenchmark {
  private static final byte[] BODY = "pong".getBytes(StandardCharsets.US_ASCII);

  private Http2StreamTable streams;
  private HpackDecoder decoder;
  private byte[] requestBlock;
  private int requestLength;

  @Setup
  public void setup() {
    streams = new Http2StreamTable(1);
    decoder = new HpackDecoder();
    ByteWriter block = new ByteWriter(64);
    HpackEncoder.writeIndexed(block, 2);
    HpackEncoder.writeIndexed(block, 7);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 4, "/ping".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);
    requestBlock = block.array();
    requestLength = block.length();
    lifecycle();
  }

  @Benchmark
  public int lifecycle() {
    Http2Stream stream = streams.acquire(1);
    decoder.decode(requestBlock, 0, requestLength, stream.headerBlock());
    stream.assembleRequest(null, null);
    Response response = stream.resetResponse().body(BODY);
    stream
        .responseWriter()
        .prepare(response, 1, false, false, true, false, false, 16_384, 32_768, 65_535);
    int bytes = stream.responseWriter().length();
    streams.remove(1);
    streams.release(stream);
    return bytes;
  }
}
