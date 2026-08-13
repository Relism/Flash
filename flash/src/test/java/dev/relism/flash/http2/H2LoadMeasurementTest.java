package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@Tag("benchmark")
@EnabledIfSystemProperty(named = "h2load.executable", matches = ".+")
@EnabledIfSystemProperty(named = "nghttpd.executable", matches = ".+")
class H2LoadMeasurementTest {
  private static final int[] CONNECTIONS = {1, 10, 100, 1_000};
  private static final int[] STREAMS = {1, 10, 100};
  private static final Pattern RATE = Pattern.compile("([0-9.]+) req/s");
  private static final Pattern REQUESTS =
      Pattern.compile("requests: (\\d+) total, .*? (\\d+) succeeded, (\\d+) failed");

  private FlashApp app;
  private Process reference;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
    if (reference != null) reference.destroyForcibly();
  }

  @Test
  void measureFlashAndNghttpdMatrix(@TempDir Path directory) throws Exception {
    int flashPort = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(flashPort)
                .http2CleartextEnabled(true)
                .h2MaxStreamsCreatedPerInterval(Integer.MAX_VALUE)
                .h2MaxStreamsPerConnection(0)
                .build());
    app.get("/index.html", (request, response) -> "flash-load");
    app.start();

    int referencePort = freePort();
    Files.writeString(directory.resolve("index.html"), "flash-load");
    ProcessBuilder server =
        new ProcessBuilder(
            System.getProperty("nghttpd.executable"),
            "--no-tls",
            "--max-concurrent-streams=128",
            "-d",
            directory.toString(),
            Integer.toString(referencePort));
    applyLibraryPath(server);
    reference = server.redirectErrorStream(true).start();
    Thread.sleep(200);

    System.out.println(
        "implementation,connections,requested_streams,effective_streams,requests,requests_per_second");
    for (int connections : CONNECTIONS) {
      for (int streams : STREAMS) {
        int requests = Math.max(1_000, connections * streams);
        int effectiveStreams =
            Math.max(
                1,
                Math.min(
                    Math.min(streams, Http2Limits.MAX_CONCURRENT_STREAMS),
                    4_096 / connections));
        measure("flash", flashPort, connections, streams, effectiveStreams, requests);
        measure("nghttpd", referencePort, connections, streams, effectiveStreams, requests);
      }
    }
  }

  private static void measure(
      String implementation,
      int port,
      int connections,
      int requestedStreams,
      int effectiveStreams,
      int requests)
      throws Exception {
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("h2load.executable"));
    command.add("-n");
    command.add(Integer.toString(requests));
    command.add("-c");
    command.add(Integer.toString(connections));
    command.add("-m");
    command.add(Integer.toString(effectiveStreams));
    command.add("-t");
    command.add(Integer.toString(Math.min(8, connections)));
    command.add("http://127.0.0.1:" + port + "/index.html");
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    applyLibraryPath(builder);
    Process process = builder.start();
    assertTrue(process.waitFor(Duration.ofMinutes(2).toMillis(), TimeUnit.MILLISECONDS));
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    Matcher requestsResult = REQUESTS.matcher(output);
    assertTrue(requestsResult.find(), output);
    assertEquals(requests, Integer.parseInt(requestsResult.group(1)), output);
    assertEquals(requests, Integer.parseInt(requestsResult.group(2)), output);
    assertEquals(0, Integer.parseInt(requestsResult.group(3)), output);
    Matcher rate = RATE.matcher(output);
    assertTrue(rate.find(), output);
    System.out.printf(
        "%s,%d,%d,%d,%d,%s%n",
        implementation,
        connections,
        requestedStreams,
        effectiveStreams,
        requests,
        rate.group(1));
  }

  private static void applyLibraryPath(ProcessBuilder builder) {
    String path = System.getProperty("nghttp.library.path");
    if (path != null) builder.environment().put("LD_LIBRARY_PATH", path);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
