package dev.relism.flash.routing.routers.fastpathrouter;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.SimpleHandler;
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
 * <p><b>{@code router_*}</b> exercises the shipped {@link FastPathRouterImpl#route} end to end,
 * including lazy-compiled route-table lookup, {@link FastPathRouterImpl.RouteScratch} reuse and
 * path-parameter extraction.
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
public class FastPathRouterBenchmark {

  // ── router_*: the real, shipped router, end to end ──────────────────────

  private FastPathRouterImpl router;
  private Object scratch;
  private Request staticRequest;
  private Request paramRequest;

  @Setup(Level.Trial)
  public void setupRouter() {
    router = new FastPathRouterImpl();
    RequestHandler h = new SimpleHandler((req, res) -> "ok");
    router.doRegister(HttpMethod.GET, "/health", h, new dev.relism.flash.routing.Middleware[0]);
    router.doRegister(HttpMethod.GET, "/users/{id}", h, new dev.relism.flash.routing.Middleware[0]);
    router.doRegister(
        HttpMethod.GET,
        "/users/{id}/posts/{postId}",
        h,
        new dev.relism.flash.routing.Middleware[0]);
    router.doRegister(HttpMethod.POST, "/users", h, new dev.relism.flash.routing.Middleware[0]);
    router.doRegister(
        HttpMethod.GET,
        "/api/v1/products/{category}/{id}",
        h,
        new dev.relism.flash.routing.Middleware[0]);
    router.compile();
    scratch = router.newScratch();

    staticRequest = mockRequest(HttpMethod.GET, "/health");
    paramRequest = mockRequest(HttpMethod.GET, "/users/12345/posts/67890");
  }

  @Benchmark
  public RequestHandler router_staticRoute() {
    return router.route(staticRequest, scratch);
  }

  @Benchmark
  public RequestHandler router_parametricRoute() {
    return router.route(paramRequest, scratch);
  }

  private static Request mockRequest(HttpMethod method, String path) {
    byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
    FastPathViews.RequestByteView pathView =
        new FastPathViews.RequestByteView(bytes, 0, bytes.length);
    dev.relism.flash.models.RequestLine line =
        new dev.relism.flash.models.RequestLine(
            method,
            pathView,
            null,
            new FastPathViews.RequestByteView("HTTP/1.1".getBytes(StandardCharsets.UTF_8), 0, 8),
            new dev.relism.flash.models.Http1HeaderMap());
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
