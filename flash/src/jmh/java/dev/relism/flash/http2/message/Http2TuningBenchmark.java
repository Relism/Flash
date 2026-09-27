package dev.relism.flash.http2.message;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.PreEncodedHeader;
import dev.relism.flash.http.Response;
import java.io.IOException;
import java.io.InputStream;
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

/** Factorial measurements for the response knobs considered during tuning. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class Http2TuningBenchmark {
  @Param({"false", "true"})
  public boolean huffmanDynamicValues;

  @Param({"16384", "65536", "1048576"})
  public int maxFrameSize;

  private final byte[] largeBody = new byte[1024 * 1024];
  private Http2ResponseWriter writer;
  private Response response;
  private Response streamingResponse;
  private ResettableInputStream source;

  @Setup(Level.Trial)
  public void setup() {
    writer = new Http2ResponseWriter();
    response =
        new Response(200, "hello", ContentType.JSON)
            .header(new PreEncodedHeader("cache-control", "private, max-age=60"))
            .header(new PreEncodedHeader("x-request-id", "d7bca219-6dd4-4ef0-a881-f21931e249c7"));
    source = new ResettableInputStream(largeBody);
    streamingResponse = new Response(200, ContentType.BINARY).stream(source, largeBody.length);
  }

  @Benchmark
  public int encodeResponseHeaders() {
    writer.prepare(
        response,
        1,
        false,
        true,
        true,
        huffmanDynamicValues,
        false,
        maxFrameSize,
        32_768,
        65_535);
    return writer.length();
  }

  @Benchmark
  public int streamOneMiB() throws IOException {
    source.rewind();
    writer.startFlowControlled(
        streamingResponse,
        1,
        false,
        false,
        true,
        huffmanDynamicValues,
        false,
        maxFrameSize,
        32_768,
        maxFrameSize);
    int wireBytes = writer.length();
    while (!writer.finished()) {
      writer.resume(maxFrameSize, maxFrameSize);
      wireBytes += writer.length();
    }
    return wireBytes;
  }

  private static final class ResettableInputStream extends InputStream {
    private final byte[] bytes;
    private int position;

    private ResettableInputStream(byte[] bytes) {
      this.bytes = bytes;
    }

    private void rewind() {
      position = 0;
    }

    @Override
    public int read() {
      return position == bytes.length ? -1 : bytes[position++] & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      if (position == bytes.length) return -1;
      int count = Math.min(length, bytes.length - position);
      System.arraycopy(bytes, position, target, offset, count);
      position += count;
      return count;
    }
  }
}
