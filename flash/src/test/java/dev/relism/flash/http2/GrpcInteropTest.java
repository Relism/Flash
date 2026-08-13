package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

@Tag("interop")
@EnabledIfSystemProperty(named = "grpcurl.executable", matches = ".+")
class GrpcInteropTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void grpcurlCompletesUnaryStreamingAndErrorCalls(@TempDir Path directory) throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    app.post("/flash.test.Echo/Unary", (request, response) ->
        response.type("application/grpc")
            .body(request.body().bytes())
            .trailer("grpc-status", "0"));
    app.post("/flash.test.Echo/Stream", (request, response) -> {
      byte[] message = request.body().bytes();
      return response.type("application/grpc").streaming(stream -> {
        try {
          for (int i = 0; i < 3; i++) stream.write(message, 0, message.length);
          stream.trailer("grpc-status", "0");
        } catch (Exception failure) {
          throw new RuntimeException(failure);
        }
      });
    });
    app.post("/flash.test.Echo/Fail", (request, response) ->
        response.type("application/grpc")
            .trailer("grpc-status", "3")
            .trailer("grpc-message", "invalid request"));
    app.start();

    Path proto = directory.resolve("echo.proto");
    Files.writeString(proto, """
        syntax = "proto3";
        package flash.test;
        service Echo {
          rpc Unary (Message) returns (Message);
          rpc Stream (Message) returns (stream Message);
          rpc Fail (Message) returns (Message);
        }
        message Message { string value = 1; }
        """);

    Result unary = call(directory, port, "Unary");
    assertEquals(0, unary.exitCode);
    assertTrue(unary.output.contains("hello"), unary.output);

    Result streaming = call(directory, port, "Stream");
    assertEquals(0, streaming.exitCode);
    assertEquals(3, occurrences(streaming.output, "hello"), streaming.output);

    Result error = call(directory, port, "Fail");
    assertTrue(error.exitCode != 0);
    assertTrue(error.output.contains("InvalidArgument"), error.output);
    assertTrue(error.output.contains("invalid request"), error.output);
  }

  private static Result call(Path directory, int port, String method) throws Exception {
    Process process = new ProcessBuilder(
        System.getProperty("grpcurl.executable"),
        "-plaintext",
        "-import-path", directory.toString(),
        "-proto", "echo.proto",
        "-d", "{\"value\":\"hello\"}",
        "127.0.0.1:" + port,
        "flash.test.Echo/" + method)
        .redirectErrorStream(true)
        .start();
    assertTrue(process.waitFor(10, TimeUnit.SECONDS), "grpcurl timed out");
    return new Result(process.exitValue(),
        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
  }

  private static int occurrences(String text, String needle) {
    int count = 0;
    int position = 0;
    while ((position = text.indexOf(needle, position)) >= 0) {
      count++;
      position += needle.length();
    }
    return count;
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private record Result(int exitCode, String output) {}
}
