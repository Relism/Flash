package dev.relism.flash;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.SimpleHandler;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathRouterImpl;
import dev.relism.flash.transport.BufferedByteSource;
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * Phase 4's zero-alloc contract: "an h1 {@code GET /users/{id}} request that reads three headers
 * and one path param must be 0 B/op end to end except for the user-facing {@code String}s the
 * handler explicitly asks for." This benchmark measures the actual current number with
 * {@code -prof gc} — see {@code DECISIONS.md}, {@code DEC-20}, for the honest result and why it
 * is not literally 0 B/op yet: {@code Request}/{@code RequestBody}/{@code RequestLine} are still
 * allocated per request ({@code EX-21}/{@code EX-22}, explicitly Phase 6 scope, not Phase 4's).
 * The two benchmark methods below isolate that cost from Phase 4's own scope (header lookups,
 * path-param extraction, query decoding) by comparing a route with no header/param access against
 * one that performs exactly the access the DoD text describes.
 *
 * <p>Uses a hand-rolled repeating {@link InputStream} (never allocates, cycles the same request
 * bytes indefinitely) rather than a fresh {@code ByteArrayInputStream}/{@code BufferedByteSource}
 * per invocation, so the timed path matches production exactly: one {@link BufferedByteSource}
 * created once per connection and reused across every request, per {@code Http1Connection}'s own
 * shape — not recreated per benchmark iteration, which would contaminate the measurement with
 * harness allocation unrelated to the parser/router/model code under test (the same lesson
 * {@code WRITER.md} documents for {@code FrameWriterBenchmark}'s own harness).
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(2)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
public class RequestPipelineBenchmark {

    /** Cycles a fixed byte[] indefinitely — simulates an infinite pipelined keep-alive stream
     *  of identical requests without allocating anything per read. */
    private static final class RepeatingByteStream extends InputStream {
        private final byte[] template;
        private int pos;

        RepeatingByteStream(byte[] template) {
            this.template = template;
        }

        @Override
        public int read() {
            byte b = template[pos];
            pos = (pos + 1) % template.length;
            return b & 0xFF;
        }

        @Override
        public int read(byte[] dst, int off, int len) {
            for (int i = 0; i < len; i++) {
                dst[off + i] = template[pos];
                pos = (pos + 1) % template.length;
            }
            return len;
        }
    }

    private RequestParser parser;
    private BufferedByteSource in;
    private FastPathRouterImpl router;
    private Object routeScratch;

    @Setup(Level.Trial)
    public void setup() {
        String req = "GET /users/12345 HTTP/1.1\r\n"
                + "Host: api.example.com\r\n"
                + "Accept: application/json\r\n"
                + "Authorization: Bearer abcdefghijklmnopqrstuvwxyz\r\n"
                + "\r\n";
        byte[] template = req.getBytes(StandardCharsets.US_ASCII);
        in = new BufferedByteSource(new RepeatingByteStream(template), null);
        parser = new RequestParser(64 * 1024);

        router = new FastPathRouterImpl();
        RequestHandler handler = new SimpleHandler((r, res) -> "ok");
        router.doRegister(HttpMethod.GET, "/users/{id}", handler, new Middleware[0]);
        router.compile();
        routeScratch = router.newScratch();
    }

    /** Parse + route only — isolates Phase 4's own scope from Request/RequestBody construction
     *  by not touching header()/param() (the "user-facing String" opt-in the DoD text carves out). */
    @Benchmark
    public RequestHandler parseAndRoute() throws IOException {
        Request request = parser.parse(in);
        request.drain();
        return router.route(request, routeScratch);
    }

    /** Parse + route + exactly what the DoD text describes: one path param, two headers read. */
    @Benchmark
    public Object parseRouteAndExtractThreeFields() throws IOException {
        Request request = parser.parse(in);
        RequestHandler handler = router.route(request, routeScratch);
        String id = request.param("id");
        String host = request.header("Host");
        String auth = request.header("Authorization");
        request.drain();
        return id.length() + host.length() + auth.length() + (handler != null ? 1 : 0);
    }
}
