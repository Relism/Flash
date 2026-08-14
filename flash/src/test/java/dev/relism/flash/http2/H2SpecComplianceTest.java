package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import java.nio.charset.StandardCharsets;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.w3c.dom.Document;
import org.w3c.dom.NodeList;

@EnabledIfSystemProperty(named = "h2spec.executable", matches = ".+")
class H2SpecComplianceTest {
  private static final String VERSION = "2.6.0";
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void cleartextSuiteHasNoFailuresOrSkips(@TempDir Path directory) throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    registerProbeRoutes();
    app.start();

    runH2Spec(port, false, directory.resolve("h2spec-h2c.xml"));
  }

  @Test
  void tlsSuiteHasNoFailuresOrSkips(@TempDir Path directory) throws Exception {
    int port = freePort();
    Path keystore =
        TestKeystores.build(
            directory,
            "h2spec.p12",
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
    registerProbeRoutes();
    app.start();

    runH2Spec(port, true, directory.resolve("h2spec-tls.xml"));
  }

  private void registerProbeRoutes() {
    app.get("/", (request, response) -> probeResponse());
    app.post("/", (request, response) -> probeResponse());
  }

  private static String probeResponse() {
    // h2spec deliberately writes illegal follow-up frames immediately after END_STREAM. Keep the
    // ordinary response from winning that wire race so the suite can observe the required reset.
    LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
    return "flash-compliance";
  }

  private static void runH2Spec(int port, boolean tls, Path report) throws Exception {
    String executable = System.getProperty("h2spec.executable");
    ProcessResult version = run(List.of(executable, "--version"), Duration.ofSeconds(5));
    assertEquals(0, version.exitCode, version.output);
    assertTrue(version.output.contains(VERSION), "unexpected h2spec version: " + version.output);

    List<String> command = new ArrayList<>();
    command.add(executable);
    command.add("--host");
    command.add(tls ? "localhost" : "127.0.0.1");
    command.add("--port");
    command.add(Integer.toString(port));
    command.add("--timeout");
    command.add("5");
    command.add("--junit-report");
    command.add(report.toString());
    if (tls) {
      command.add("--tls");
      command.add("--insecure");
    } else {
      command.add("generic");
      command.add("hpack");
      command.add("http2/3.5/1");
      command.add("http2/4");
      command.add("http2/5");
      command.add("http2/6");
      command.add("http2/7");
      command.add("http2/8");
    }

    ProcessResult result = run(command, Duration.ofMinutes(3));
    assertEquals(0, result.exitCode, result.output);
    assertReportHasNoFailuresOrSkips(report, result.output);
  }

  private static ProcessResult run(List<String> command, Duration timeout) throws Exception {
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (!completed) {
      process.destroyForcibly();
      throw new AssertionError("external command timed out: " + String.join(" ", command));
    }
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    return new ProcessResult(process.exitValue(), output);
  }

  private static void assertReportHasNoFailuresOrSkips(Path report, String output)
      throws Exception {
    assertTrue(Files.isRegularFile(report), "h2spec did not create its JUnit report\n" + output);
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    Document document = factory.newDocumentBuilder().parse(report.toFile());
    NodeList failures = document.getElementsByTagName("failure");
    NodeList errors = document.getElementsByTagName("error");
    NodeList skipped = document.getElementsByTagName("skipped");
    assertEquals(0, failures.getLength(), output);
    assertEquals(0, errors.getLength(), output);
    assertEquals(0, skipped.getLength(), output);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private record ProcessResult(int exitCode, String output) {}
}
