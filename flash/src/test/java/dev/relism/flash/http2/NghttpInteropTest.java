package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@EnabledIfSystemProperty(named = "nghttp.executable", matches = ".+")
class NghttpInteropTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void verboseFrameTraceIsCorrectForTlsAndCleartext(@TempDir Path directory) throws Exception {
    exercise(directory, true);
    stop();
    app = null;
    exercise(directory, false);
  }

  private void exercise(Path directory, boolean tls) throws Exception {
    int port = freePort();
    FlashConfiguration.FlashConfigurationBuilder builder =
        FlashConfiguration.builder().host("127.0.0.1").port(port);
    if (tls) {
      Path keystore =
          TestKeystores.build(
              directory,
              "nghttp.p12",
              "changeit",
              TestKeystores.Entry.of("server", "localhost", "localhost"));
      builder.tls(TlsConfig.keystore(keystore, "changeit")).http2Enabled(true);
    } else {
      builder.http2CleartextEnabled(true);
    }
    byte[] large = new byte[2 * 1024 * 1024 + 29];
    app = FlashApp.create(builder.build());
    app.get("/get", (request, response) -> "nghttp-get");
    app.post("/post", (request, response) -> "uploaded-" + request.body().bytes().length);
    app.get("/large", (request, response) -> response.body(large));
    app.start();

    String origin = (tls ? "https://localhost:" : "http://127.0.0.1:") + port;
    Path upload = directory.resolve("nghttp-upload.bin");
    Files.write(upload, large);
    assertTrace(run(origin + "/get", tls));
    assertTrace(run(origin + "/post", tls, "-d", upload.toString()));
    assertTrace(run(origin + "/large", tls, "-n"));
  }

  private static String run(String uri, boolean tls, String... extra) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(System.getProperty("nghttp.executable"));
    command.add("-v");
    command.add("-t");
    command.add("30s");
    if (tls) command.add("-y");
    command.addAll(List.of(extra));
    command.add(uri);
    ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
    String libraryPath = System.getProperty("nghttp.library.path");
    if (libraryPath != null) builder.environment().put("LD_LIBRARY_PATH", libraryPath);
    Process process = builder.start();
    assertTrue(process.waitFor(Duration.ofSeconds(40).toMillis(), TimeUnit.MILLISECONDS));
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), output);
    return output;
  }

  private static void assertTrace(String trace) {
    assertTrue(trace.contains("recv SETTINGS frame"), trace);
    assertTrue(trace.contains("recv HEADERS frame"), trace);
    assertTrue(trace.contains(":status: 200"), trace);
    assertTrue(trace.contains("recv DATA frame"), trace);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
