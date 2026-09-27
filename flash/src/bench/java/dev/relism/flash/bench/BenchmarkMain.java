package dev.relism.flash.bench;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Real-server, real-network throughput and latency benchmark: boots one live Flash server on
 * loopback exposing {@code GET /hello}, then drives it end to end — real sockets, real accept
 * loop, real routing and response serialization — with independent HTTP clients across protocols
 * and concurrency levels. This is not a component-scoped JMH microbenchmark; it is the same shape
 * of measurement a tool like {@code h2load} or {@code wrk} gives any other server.
 *
 * <p>Never wired into the build or CI — run manually with: {@code mvn -pl flash -Pbench
 * exec:java}. Override scenario length with {@code -Dflash.bench.warmupSeconds} / {@code
 * -Dflash.bench.measureSeconds} (defaults: 2 / 5).
 */
public final class BenchmarkMain {

  private static final int[] CONCURRENCY_LEVELS = {1, 8, 32, 128};

  public static void main(String[] args) throws Exception {
    Duration warmup = seconds("flash.bench.warmupSeconds", 2);
    Duration measurement = seconds("flash.bench.measureSeconds", 5);

    int port = freePort();
    Server server =
        Server.start(
            App.create().get("/hello", (request, response) -> "hello"),
            ServerConfig.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());

    try {
      URI target = URI.create("http://127.0.0.1:" + port + "/hello");
      Report.print(runAllScenarios(target, warmup, measurement));
    } finally {
      server.stop().join();
    }
  }

  private static List<LoadResult> runAllScenarios(URI target, Duration warmup, Duration measurement)
      throws InterruptedException {
    List<LoadResult> results = new ArrayList<>();
    for (int concurrency : CONCURRENCY_LEVELS) {
      results.add(
          new Http1Driver()
              .run("http/1.1 c=" + concurrency, target, concurrency, warmup, measurement));
    }
    return results;
  }

  private static Duration seconds(String property, int fallback) {
    return Duration.ofSeconds(Long.getLong(property, fallback));
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
