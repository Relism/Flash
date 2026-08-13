package dev.relism.flash.http2.client;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.MutableHeaderMap;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Http2ClientTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void reusesOriginConnectionAndExchangesFlowControlledBodiesAndTrailers() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    app.post(
        "/relay",
        (request, response) -> {
          byte[] body = request.body().bytes();
          String checksum = request.trailers().first("x-request-checksum");
          return response
              .header("x-upstream", request.header("x-forwarded-test"))
              .body(body)
              .trailer("x-response-checksum", checksum);
        });
    app.start();

    byte[] body = new byte[2 * 1024 * 1024 + 31];
    for (int i = 0; i < body.length; i++) body[i] = (byte) (i * 29);
    MutableHeaderMap requestHeaders = fields("x-forwarded-test", "yes");
    MutableHeaderMap requestTrailers = fields("x-request-checksum", "valid");

    try (Http2Client client = new Http2Client()) {
      URI uri = URI.create("http://127.0.0.1:" + port + "/relay");
      Http2ClientResponse first =
          client.exchange(uri, HttpMethod.POST, requestHeaders, body, requestTrailers);
      Http2ClientResponse second =
          client.exchange(
              uri,
              HttpMethod.POST,
              requestHeaders,
              "again".getBytes(StandardCharsets.UTF_8),
              requestTrailers);

      assertEquals(200, first.statusCode());
      assertEquals("yes", first.headers().first("x-upstream"));
      assertArrayEquals(body, first.body());
      assertEquals("valid", first.trailers().first("x-response-checksum"));
      assertArrayEquals("again".getBytes(StandardCharsets.UTF_8), second.body());
      assertEquals(1, client.pooledConnectionCount());
    }
  }

  @Test
  void negotiatesTlsAlpnAndVerifiesTheUpstreamHostname(@TempDir Path directory) throws Exception {
    int port = freePort();
    Path keystore =
        TestKeystores.build(
            directory,
            "http2-client.p12",
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
    app.get("/secure", (request, response) -> "tls-h2");
    app.start();

    try (Http2Client client = new Http2Client(TestKeystores.trustAllClientContext())) {
      Http2ClientResponse response =
          client.get(URI.create("https://localhost:" + port + "/secure"));
      assertEquals(200, response.statusCode());
      assertEquals("tls-h2", new String(response.body(), StandardCharsets.UTF_8));
    }
  }

  private static MutableHeaderMap fields(String name, String value) {
    MutableHeaderMap headers = new MutableHeaderMap();
    byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    headers.add(nameBytes, 0, nameBytes.length, valueBytes, 0, valueBytes.length);
    return headers;
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
