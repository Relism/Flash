package dev.relism.flash.routing;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.bytes.FastPathViews;
import dev.relism.fpr.core.internal.runtime.ByteCompare;
import java.nio.charset.StandardCharsets;
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

/**
 * Two related but distinct measurements of router and byte-comparison performance.
 *
 * <p><b>{@code router_*}</b> exercises the shipped {@link Router#route} end to end, including
 * route-table lookup, {@link Router.Scratch} reuse and path-parameter extraction.
 *
 * <p><b>{@code byteCompare_*}</b> directly compares {@code ByteCompare.equals} with its
 * word-at-a-time path enabled and disabled over representative array-backed content. This is
 * separate because router matching uses the composite, non-array-backed {@link
 * FastPathViews.MethodPathByteView}, so the router measurements cannot expose the word path's
 * effect.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RouterBenchmark {

  // ── router_*: the real, shipped router, end to end ──────────────────────

  private Router router;
  private Router.Scratch scratch;
  private Request staticRequest;
  private Request paramRequest;

  @Setup(Level.Trial)
  public void setupRouter() {
    router = new Router();
    Next h = exchange -> exchange.answer("ok");
    router.add(HttpMethod.GET, "/health", h);
    router.add(HttpMethod.GET, "/users/{id}", h);
    router.add(HttpMethod.GET, "/users/{id}/posts/{postId}", h);
    router.add(HttpMethod.POST, "/users", h);
    router.add(HttpMethod.GET, "/api/v1/products/{category}/{id}", h);
    router.compile(Errors.notFound(false)::prepare);
    scratch = router.newScratch();

    staticRequest = mockRequest(HttpMethod.GET, "/health");
    paramRequest = mockRequest(HttpMethod.GET, "/users/12345/posts/67890");
  }

  @Benchmark
  public Next router_staticRoute() {
    return router.route(staticRequest, scratch);
  }

  @Benchmark
  public Next router_parametricRoute() {
    return router.route(paramRequest, scratch);
  }

  private static Request mockRequest(HttpMethod method, String path) {
    byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
    FastPathViews.RequestByteView pathView =
        new FastPathViews.RequestByteView(bytes, 0, bytes.length);
    dev.relism.flash.http.RequestLine line =
        new dev.relism.flash.http.RequestLine(
            method,
            pathView,
            null,
            new FastPathViews.RequestByteView("HTTP/1.1".getBytes(StandardCharsets.UTF_8), 0, 8),
            new dev.relism.flash.http.Http1HeaderMap());
    return new Request(line, new byte[0]);
  }

  // ── byteCompare_*: word-at-a-time comparison in isolation ──────────────

  private FastPathViews.RequestByteView cmpView;
  private byte[] cmpOther;

  @Setup(Level.Trial)
  public void setupByteCompare() {
    byte[] content = "/api/v1/products/electronics/00012345".getBytes(StandardCharsets.US_ASCII);
    cmpView = new FastPathViews.RequestByteView(content, 0, content.length);
    cmpOther = content.clone();
  }

  @Benchmark
  public boolean byteCompare_longPath() {
    return ByteCompare.equals(cmpView, 0, cmpOther, 0, cmpOther.length, true);
  }

  @Benchmark
  public boolean byteCompare_byteAtATime() {
    return ByteCompare.equals(cmpView, 0, cmpOther, 0, cmpOther.length, false);
  }
}
