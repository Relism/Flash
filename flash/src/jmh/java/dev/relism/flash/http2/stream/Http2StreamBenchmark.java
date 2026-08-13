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
  private static final byte[] POST_BODY = new byte[1024];

  private Http2StreamTable streams;
  private HpackDecoder decoder;
  private byte[] requestBlock;
  private int requestLength;
  private byte[] postBlock;
  private int postLength;

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
    ByteWriter post = new ByteWriter(96);
    HpackEncoder.writeIndexed(post, 3);
    HpackEncoder.writeIndexed(post, 7);
    HpackEncoder.writeLiteralWithNameIndex(
        post, 4, "/ping".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        post, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        post, 28, "1024".getBytes(StandardCharsets.US_ASCII), false);
    postBlock = post.array();
    postLength = post.length();
    lifecycle();
    postOneKiB();
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

  /** Unary request shape: HPACK decode, one 1 KiB DATA payload, assembly and fixed response. */
  @Benchmark
  public int postOneKiB() {
    Http2Stream stream = streams.acquire(1);
    decoder.decode(postBlock, 0, postLength, stream.headerBlock());
    stream.prepareRequestBody(null, false);
    stream.receiveData(POST_BODY, 0, POST_BODY.length, POST_BODY.length);
    stream.finishRequestBody();
    stream.assembleRequest(null, null);
    stream
        .responseWriter()
        .prepare(
            stream.resetResponse().body(BODY),
            1,
            false,
            false,
            true,
            false,
            false,
            16_384,
            32_768,
            65_535);
    int bytes = stream.responseWriter().length();
    streams.remove(1);
    streams.release(stream);
    return bytes;
  }
}
