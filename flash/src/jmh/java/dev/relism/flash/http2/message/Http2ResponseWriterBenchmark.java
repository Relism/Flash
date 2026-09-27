package dev.relism.flash.http2.message;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.PreEncodedHeader;
import dev.relism.flash.http.Response;
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

/** Measures the steady-state allocation cost of a representative fixed HTTP/2 response. */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class Http2ResponseWriterBenchmark {
  private Http2ResponseWriter writer;
  private Response response;

  @Setup
  public void setup() {
    writer = new Http2ResponseWriter();
    response =
        new Response(200, "hello", ContentType.JSON)
            .header(new PreEncodedHeader("cache-control", "no-store"))
            .header(new PreEncodedHeader("x-trace", "abc123"));
    writer.prepare(response, 1, false, true, true, false, false, 16_384, 16_384, 65_535);
  }

  @Benchmark
  public int encodeResponse() {
    writer.prepare(response, 1, false, true, true, false, false, 16_384, 16_384, 65_535);
    return writer.length();
  }
}
