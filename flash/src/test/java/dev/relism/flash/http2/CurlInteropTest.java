package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

@EnabledIfSystemProperty(named = "curl.executable", matches = ".+")
class CurlInteropTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void tlsGetPostLargeUploadAndLargeDownload(@TempDir Path directory) throws Exception {
    int port = freePort();
    Path keystore =
        TestKeystores.build(
            directory,
            "curl.p12",
            "changeit",
            TestKeystores.Entry.of("server", "localhost", "localhost"));
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .tls(TlsConfig.keystore(keystore, "changeit"))
                .http2Enabled(true)
                .build());
    exercise(directory, "https://localhost:" + port, "--http2", "--insecure");
  }

  @Test
  void cleartextGetPostLargeUploadAndLargeDownload(@TempDir Path directory) throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    exercise(directory, "http://127.0.0.1:" + port, "--http2-prior-knowledge");
  }

  private void exercise(Path directory, String origin, String... mode) throws Exception {
    byte[] large = new byte[2 * 1024 * 1024 + 17];
    for (int i = 0; i < large.length; i++) large[i] = (byte) (i * 31);
    app.get("/get", (request, response) -> "curl-get");
    app.post("/post", (request, response) -> request.body().bytes());
    app.get("/large", (request, response) -> response.body(large));
    app.start();

    Path upload = directory.resolve("upload.bin");
    Path output = directory.resolve("output.bin");
    Files.write(upload, large);
    assertArrayEquals(
        "curl-get".getBytes(StandardCharsets.US_ASCII),
        runCurl(output, origin + "/get", mode));
    assertArrayEquals(
        "small-post".getBytes(StandardCharsets.US_ASCII),
        runCurl(output, origin + "/post", append(mode, "--data-binary", "small-post")));
    assertArrayEquals(
        large,
        runCurl(output, origin + "/post", append(mode, "--data-binary", "@" + upload)));
    assertArrayEquals(large, runCurl(output, origin + "/large", mode));
  }

  private static byte[] runCurl(Path output, String url, String... options) throws Exception {
    String executable = System.getProperty("curl.executable");
    List<String> command = new ArrayList<>();
    command.add(executable);
    command.add("--silent");
    command.add("--show-error");
    command.add("--fail");
    command.addAll(List.of(options));
    command.add("--output");
    command.add(output.toString());
    command.add(url);
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    assertTrue(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS));
    String diagnostics =
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    assertEquals(0, process.exitValue(), diagnostics);
    return Files.readAllBytes(output);
  }

  private static String[] append(String[] values, String... suffix) {
    String[] result = new String[values.length + suffix.length];
    System.arraycopy(values, 0, result, 0, values.length);
    System.arraycopy(suffix, 0, result, values.length, suffix.length);
    return result;
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
