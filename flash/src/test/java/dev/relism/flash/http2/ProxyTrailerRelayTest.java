package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.proxy.HttpProxy;
import dev.relism.flash.http2.client.Http2Client;
import dev.relism.flash.http2.client.Http2ClientResponse;
import dev.relism.flash.models.MutableHeaderMap;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class ProxyTrailerRelayTest {
  private FlashApp upstream;
  private FlashApp proxy;
  private Http2Client proxyUpstream;

  @AfterEach
  void stop() {
    if (proxyUpstream != null) proxyUpstream.close();
    if (proxy != null) proxy.stop().join();
    if (upstream != null) upstream.stop().join();
  }

  @Test
  void requestAndResponseTrailersSurviveH2AndH1DownstreamProxyHops() throws Exception {
    int upstreamPort = freePort();
    upstream =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(upstreamPort)
                .http2CleartextEnabled(true)
                .build());
    upstream.post(
        "/relay",
        (request, response) ->
            response
                .header("x-query", request.query("mode"))
                .header("x-private-seen", String.valueOf(request.header("x-private") != null))
                .body(request.body().bytes())
                .trailer("x-relayed-trailer", request.trailers().first("x-request-trailer")));
    upstream.start();

    int proxyPort = freePort();
    proxyUpstream = new Http2Client();
    proxy =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(proxyPort)
                .http2CleartextEnabled(true)
                .build());
    proxy.post(
        "/relay",
        HttpProxy.toHttp2(URI.create("http://127.0.0.1:" + upstreamPort), proxyUpstream));
    proxy.start();

    MutableHeaderMap h2Headers = fields("connection", "x-private");
    add(h2Headers, "x-private", "must-not-cross");
    MutableHeaderMap h2Trailers = fields("x-request-trailer", "from-h2");
    try (Http2Client downstream = new Http2Client()) {
      Http2ClientResponse response =
          downstream.exchange(
              URI.create("http://127.0.0.1:" + proxyPort + "/relay?mode=h2"),
              HttpMethod.POST,
              h2Headers,
              "hello-h2".getBytes(StandardCharsets.UTF_8),
              h2Trailers);
      assertEquals("hello-h2", new String(response.body(), StandardCharsets.UTF_8));
      assertEquals("h2", response.headers().first("x-query"));
      assertEquals("false", response.headers().first("x-private-seen"));
      assertEquals("from-h2", response.trailers().first("x-relayed-trailer"));
    }

    String h1 = h1Exchange(proxyPort);
    assertTrue(h1.contains("hello-h1"), h1);
    assertTrue(h1.toLowerCase().contains("x-query: h1"), h1);
    assertTrue(h1.toLowerCase().contains("x-private-seen: false"), h1);
    assertTrue(h1.toLowerCase().contains("x-relayed-trailer: from-h1"), h1);
    assertFalse(h1.contains("must-not-cross"), h1);
  }

  private static String h1Exchange(int port) throws Exception {
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket
          .getOutputStream()
          .write(
              ("POST /relay?mode=h1 HTTP/1.1\r\n"
                      + "Host: 127.0.0.1\r\n"
                      + "Connection: x-private, close\r\n"
                      + "X-Private: must-not-cross\r\n"
                      + "Transfer-Encoding: chunked\r\n"
                      + "Trailer: x-request-trailer\r\n\r\n"
                      + "8\r\nhello-h1\r\n"
                      + "0\r\nX-Request-Trailer: from-h1\r\n\r\n")
                  .getBytes(StandardCharsets.US_ASCII));
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      socket.getInputStream().transferTo(bytes);
      return bytes.toString(StandardCharsets.UTF_8);
    }
  }

  private static MutableHeaderMap fields(String name, String value) {
    MutableHeaderMap headers = new MutableHeaderMap();
    add(headers, name, value);
    return headers;
  }

  private static void add(MutableHeaderMap headers, String name, String value) {
    byte[] nameBytes = name.getBytes(StandardCharsets.US_ASCII);
    byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
    headers.add(nameBytes, 0, nameBytes.length, valueBytes, 0, valueBytes.length);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
