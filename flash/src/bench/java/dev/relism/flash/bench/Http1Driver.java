package dev.relism.flash.bench;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * HTTP/1.1 keep-alive load driver backed by the JDK's own {@link HttpClient} — an independent
 * client implementation, not Flash's own code, measuring the server end to end.
 */
final class Http1Driver implements LoadDriver {

  @Override
  public LoadResult run(
      String scenarioLabel, URI target, int concurrency, Duration warmup, Duration measurement)
      throws InterruptedException {
    HttpRequest request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(5)).GET().build();
    return LoadRunner.execute(
        scenarioLabel,
        concurrency,
        warmup,
        measurement,
        () -> {
          // One HttpClient per worker: its own connection pool, reused keep-alive across requests.
          HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
          return () -> {
            HttpResponse<Void> response =
                client.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 200) {
              throw new IllegalStateException("status " + response.statusCode());
            }
          };
        });
  }
}
