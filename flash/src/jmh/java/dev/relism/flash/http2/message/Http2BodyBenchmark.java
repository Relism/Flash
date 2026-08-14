package dev.relism.flash.http2.message;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.RequestBody;
import dev.relism.flash.models.Response;
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
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3)
@Measurement(iterations = 5)
@Fork(2)
@State(Scope.Thread)
public class Http2BodyBenchmark {
  private static final Http2RequestBody.ConsumptionListener NOOP = bytes -> {};

  private final byte[] payload = new byte[1024];
  private final byte[] streamingPayload = new byte[1024 * 1024];
  private final byte[] target = new byte[1024];
  private DataBufferPool pool;
  private Http2RequestBody source;
  private RequestBody body;
  private Response response;
  private Http2ResponseWriter responseWriter;
  private ResettableInputStream responseSource;
  private ResettableInputStream largeResponseSource;

  @Setup(Level.Trial)
  public void setup() throws IOException {
    pool = new DataBufferPool(16_384, 1);
    source = new Http2RequestBody(pool);
    body = new RequestBody();
    response = new Response(200, ContentType.BINARY);
    responseWriter = new Http2ResponseWriter();
    responseSource = new ResettableInputStream(payload);
    largeResponseSource = new ResettableInputStream(streamingPayload);
    source.begin(-1, false, NOOP);
    source.offer(1, payload, 0, payload.length, payload.length);
    source.finish(1);
    source.read(target);
  }

  @Benchmark
  public byte[] inlineBytes() {
    source.begin(payload.length, true, NOOP);
    source.offer(1, payload, 0, payload.length, payload.length);
    source.finish(1);
    body.reset(source, payload.length, null, 0, 0);
    return body.bytes();
  }

  @Benchmark
  public int streamingRead() throws IOException {
    source.begin(-1, false, NOOP);
    source.offer(1, payload, 0, payload.length, payload.length);
    source.finish(1);
    return source.read(target, 0, target.length);
  }

  @Benchmark
  public int streamingResponseFrame() throws IOException {
    responseSource.rewind();
    response.reset(200, ContentType.BINARY).stream(responseSource, payload.length);
    responseWriter.startFlowControlled(
        response, 1, false, false, true, false, false, 16_384, 32_768, 16_384);
    return responseWriter.length();
  }

  @Benchmark
  public int streamingResponseOneMiB() throws IOException {
    largeResponseSource.rewind();
    response.reset(200, ContentType.BINARY).stream(largeResponseSource, streamingPayload.length);
    responseWriter.startFlowControlled(
        response, 1, false, false, true, false, false, 16_384, 32_768, 16_384);
    int wireBytes = responseWriter.length();
    while (!responseWriter.finished()) {
      responseWriter.resume(16_384, 16_384);
      wireBytes += responseWriter.length();
    }
    return wireBytes;
  }

  private static final class ResettableInputStream extends InputStream {
    private final byte[] source;
    private int position;

    ResettableInputStream(byte[] source) {
      this.source = source;
    }

    void rewind() {
      position = 0;
    }

    @Override
    public int read() {
      return position == source.length ? -1 : source[position++] & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int length) {
      if (position == source.length) return -1;
      int count = Math.min(length, source.length - position);
      System.arraycopy(source, position, target, offset, count);
      position += count;
      return count;
    }
  }
}
