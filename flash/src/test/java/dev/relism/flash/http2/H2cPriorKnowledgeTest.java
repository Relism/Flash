package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http2.client.Http2Client;
import dev.relism.flash.http2.client.Http2ClientResponse;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class H2cPriorKnowledgeTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void priorKnowledgeRequiresItsIndependentOptIn() throws Exception {
    int disabledPort = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(disabledPort)
                .http2Enabled(true)
                .build());
    app.get("/", (request, response) -> "wrong protocol");
    app.start();

    try (Socket socket = new Socket("127.0.0.1", disabledPort)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write(Http2Preface.clientPreface());
      byte[] prefix = socket.getInputStream().readNBytes(5);
      assertArrayEquals("HTTP/".getBytes(StandardCharsets.US_ASCII), prefix);
    }
    app.stop().join();

    int enabledPort = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(enabledPort)
                .http2CleartextEnabled(true)
                .build());
    app.get("/", (request, response) -> "h2c");
    app.start();

    try (Http2Client client = new Http2Client()) {
      Http2ClientResponse response =
          client.get(URI.create("http://127.0.0.1:" + enabledPort + "/"));
      assertEquals(200, response.statusCode());
      assertEquals("h2c", new String(response.body(), StandardCharsets.UTF_8));
    }
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
