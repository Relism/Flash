package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Http2ConnectionIntegrationTest {
  private FlashApp app;

  @AfterEach
  void stopApp() {
    if (app != null) app.stop().join();
  }

  @Test
  void javaHttpClientUsesHttp2AgainstAnExistingParameterizedRoute(@TempDir Path directory)
      throws Exception {
    int port = freePort();
    Path keystore =
        TestKeystores.build(
            directory,
            "http2-route.p12",
            "changeit",
            TestKeystores.Entry.of("server", "localhost", "localhost"));
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .tls(TlsConfig.keystore(keystore, "changeit"))
                .http2Enabled(true)
                .build());
    app.get(
        "/users/{id}", (request, response) -> request.param("id") + ":" + request.header("host"));
    app.start();

    HttpClient client =
        HttpClient.newBuilder()
            .sslContext(TestKeystores.trustAllClientContext())
            .version(HttpClient.Version.HTTP_2)
            .build();
    HttpResponse<String> response =
        client.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/users/42"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString());

    assertEquals(HttpClient.Version.HTTP_2, response.version());
    assertEquals(200, response.statusCode());
    assertEquals("42:localhost:" + port, response.body());
  }

  @Test
  void javaHttpClientUploadsAndDownloadsFlowControlledBodies(@TempDir Path directory)
      throws Exception {
    int port = freePort();
    Path keystore =
        TestKeystores.build(
            directory,
            "http2-bodies.p12",
            "changeit",
            TestKeystores.Entry.of("server", "localhost", "localhost"));
    byte[] upload = new byte[2 * 1024 * 1024];
    for (int i = 0; i < upload.length; i++) upload[i] = (byte) (i * 31);
    byte[] download = new byte[2 * 1024 * 1024 + 17];
    for (int i = 0; i < download.length; i++) download[i] = (byte) (i * 17);

    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .tls(TlsConfig.keystore(keystore, "changeit"))
                .http2Enabled(true)
                .build());
    app.post("/echo", (request, response) -> request.body().bytes());
    app.get("/fixed", (request, response) -> response.body(download));
    app.get(
        "/stream",
        (request, response) -> response.chunked(new ByteArrayInputStream(download)));
    app.start();

    HttpClient client =
        HttpClient.newBuilder()
            .sslContext(TestKeystores.trustAllClientContext())
            .version(HttpClient.Version.HTTP_2)
            .build();
    HttpResponse<byte[]> echoed =
        client.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/echo"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(upload))
                .build(),
            HttpResponse.BodyHandlers.ofByteArray());
    HttpResponse<byte[]> fixed =
        client.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/fixed")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());
    HttpResponse<byte[]> streamed =
        client.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/stream")).GET().build(),
            HttpResponse.BodyHandlers.ofByteArray());

    assertArrayEquals(upload, echoed.body());
    assertArrayEquals(download, fixed.body());
    assertArrayEquals(download, streamed.body());
    assertTrue(streamed.headers().firstValue("transfer-encoding").isEmpty());
  }

  @Test
  void hundredMegabyteUploadAndDownloadRemainStreaming(@TempDir Path directory) throws Exception {
    int port = freePort();
    long length = 100L * 1024 * 1024;
    Path keystore =
        TestKeystores.build(
            directory,
            "http2-large-bodies.p12",
            "changeit",
            TestKeystores.Entry.of("server", "localhost", "localhost"));
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .tls(TlsConfig.keystore(keystore, "changeit"))
                .http2Enabled(true)
                .build());
    app.post(
        "/upload",
        (request, response) -> {
          long count = verifyPattern(request.body().stream());
          return Long.toString(count);
        });
    app.get(
        "/download",
        (request, response) -> response.stream(new PatternInputStream(length), length));
    app.start();

    HttpClient client =
        HttpClient.newBuilder()
            .sslContext(TestKeystores.trustAllClientContext())
            .version(HttpClient.Version.HTTP_2)
            .build();
    HttpResponse<String> upload =
        client.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/upload"))
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new PatternInputStream(length)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    HttpResponse<InputStream> download =
        client.send(
            HttpRequest.newBuilder(URI.create("https://localhost:" + port + "/download"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofInputStream());

    assertEquals(Long.toString(length), upload.body());
    try (InputStream body = download.body()) {
      assertEquals(length, verifyPattern(body));
    }
  }

  @Test
  void bodylessGetRunsExistingRouteAndReturnsHeadersAndData() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder().port(port).host("127.0.0.1").http2Enabled(true).build());
    app.get("/api/ping", (request, response) -> "pong");
    app.start();

    ByteWriter block = new ByteWriter(64);
    HpackEncoder.writeIndexed(block, 2);
    HpackEncoder.writeIndexed(block, 6);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 4, "/api/ping".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      socket
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
                  Http2TestFrames.frame(
                      FrameType.HEADERS,
                      FrameFlags.END_HEADERS | FrameFlags.END_STREAM,
                      1,
                      Arrays.copyOf(block.array(), block.length()))));
      socket.getOutputStream().flush();

      ByteWriter responseBlock = new ByteWriter(128);
      byte[] body = null;
      for (int i = 0; i < 10 && body == null; i++) {
        Http2TestFrames.WireFrame frame = readFrame(socket.getInputStream());
        if (frame.streamId() != 1) continue;
        if (frame.type() == FrameType.HEADERS.code()
            || frame.type() == FrameType.CONTINUATION.code()) {
          responseBlock.writeBytes(frame.payload());
        } else if (frame.type() == FrameType.DATA.code()) {
          body = frame.payload();
        }
      }

      List<String> fields = new ArrayList<>();
      new HpackDecoder()
          .decode(
              responseBlock.array(),
              0,
              responseBlock.length(),
              (name, value, never) -> fields.add(ascii(name) + "=" + ascii(value)));
      assertTrue(fields.contains(":status=200"));
      assertTrue(fields.contains("content-length=4"));
      assertEquals("pong", new String(body, StandardCharsets.US_ASCII));
    }
  }

  @Test
  void resetQueuedStreamCannotReleaseOrReuseItBeforeDispatchObservesCancellation()
      throws Exception {
    int port = freePort();
    AtomicInteger calls = new AtomicInteger();
    app =
        FlashApp.create(
            FlashConfiguration.builder().port(port).host("127.0.0.1").http2Enabled(true).build());
    app.get(
        "/queued",
        (request, response) -> {
          calls.incrementAndGet();
          return "ok";
        });
    app.start();

    ByteWriter block = new ByteWriter(64);
    HpackEncoder.writeIndexed(block, 2);
    HpackEncoder.writeIndexed(block, 6);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 4, "/queued".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);
    byte[] headers = Arrays.copyOf(block.array(), block.length());
    byte[] cancel = {0, 0, 0, 8};

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      socket
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
                  Http2TestFrames.frame(
                      FrameType.HEADERS,
                      FrameFlags.END_HEADERS | FrameFlags.END_STREAM,
                      1,
                      headers),
                  Http2TestFrames.frame(FrameType.RST_STREAM, 0, 1, cancel),
                  Http2TestFrames.frame(
                      FrameType.HEADERS,
                      FrameFlags.END_HEADERS | FrameFlags.END_STREAM,
                      3,
                      headers)));
      socket.getOutputStream().flush();

      Http2TestFrames.WireFrame response = null;
      for (int i = 0; i < 10; i++) {
        Http2TestFrames.WireFrame frame = readFrame(socket.getInputStream());
        assertFalse(
            frame.streamId() == 1
                && (frame.type() == FrameType.HEADERS.code()
                    || frame.type() == FrameType.DATA.code()),
            "a reset request must not produce a response");
        if (frame.streamId() == 3 && frame.type() == FrameType.DATA.code()) {
          response = frame;
          break;
        }
      }
      assertNotNull(response);
      assertEquals("ok", new String(response.payload(), StandardCharsets.US_ASCII));
      assertEquals(1, calls.get());
    }
  }

  @Test
  void h2cTransportDispatchesControlFramesWithoutRunningApplicationWork() throws Exception {
    int port = freePort();
    AtomicBoolean handlerEntered = new AtomicBoolean();
    app =
        FlashApp.create(
            FlashConfiguration.builder().port(port).host("127.0.0.1").http2Enabled(true).build());
    app.get(
        "/",
        (request, response) -> {
          handlerEntered.set(true);
          Thread.sleep(5_000);
          return "late";
        });
    app.start();

    byte[] clientPing = "client!!".getBytes(StandardCharsets.US_ASCII);
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      socket
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
                  Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1, new byte[0]),
                  Http2TestFrames.frame(FrameType.PING, 0, 0, clientPing)));
      socket.getOutputStream().flush();

      byte[] shutdownPing = null;
      boolean sawClientPong = false;
      for (int i = 0; i < 8 && !sawClientPong; i++) {
        Http2TestFrames.WireFrame frame = readFrame(socket.getInputStream());
        if (frame.type() == FrameType.PING.code()) {
          if ((frame.flags() & FrameFlags.ACK) != 0 && Arrays.equals(clientPing, frame.payload())) {
            sawClientPong = true;
          } else if ((frame.flags() & FrameFlags.ACK) == 0) {
            shutdownPing = frame.payload();
          }
        }
      }

      assertTrue(sawClientPong, "PING must be processed while a route exists");
      assertFalse(handlerEntered.get(), "the connection demux must not execute handlers");
      if (shutdownPing != null) {
        socket
            .getOutputStream()
            .write(Http2TestFrames.frame(FrameType.PING, FrameFlags.ACK, 0, shutdownPing));
        socket.getOutputStream().flush();
      }
    }
  }

  @Test
  void serverStopInitiatesTwoStageGoAwayOnIdleConnection() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .http2Enabled(true)
                .shutdownDrainTimeoutMs(5_000)
                .build());
    app.start();

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      socket
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0])));
      socket.getOutputStream().flush();

      readFrame(socket.getInputStream()); // server SETTINGS
      readFrame(socket.getInputStream()); // initial connection WINDOW_UPDATE
      readFrame(socket.getInputStream()); // SETTINGS ACK

      CompletableFuture<Void> stopped = app.stop();
      app = null;
      Http2TestFrames.WireFrame firstGoAway = readUntil(socket, FrameType.GOAWAY);
      assertEquals(Integer.MAX_VALUE, Http2TestFrames.readInt(firstGoAway.payload(), 0));
      Http2TestFrames.WireFrame ping = readUntil(socket, FrameType.PING);
      socket
          .getOutputStream()
          .write(Http2TestFrames.frame(FrameType.PING, FrameFlags.ACK, 0, ping.payload()));
      socket.getOutputStream().flush();
      Http2TestFrames.WireFrame finalGoAway = readUntil(socket, FrameType.GOAWAY);
      assertEquals(0, Http2TestFrames.readInt(finalGoAway.payload(), 0));
      stopped.get(5, TimeUnit.SECONDS);
    }
  }

  @Test
  void protocolStateDoesNotLeakAcrossConsecutiveConnections() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder().port(port).host("127.0.0.1").http2Enabled(true).build());
    app.start();

    try (Socket first = new Socket("127.0.0.1", port)) {
      first.setSoTimeout(5_000);
      first
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.frame(FrameType.PING, 0, 0, new byte[8])));
      first.getOutputStream().flush();
      readUntil(first, FrameType.GOAWAY);
    }

    byte[] opaque = "isolated".getBytes(StandardCharsets.US_ASCII);
    try (Socket second = new Socket("127.0.0.1", port)) {
      second.setSoTimeout(5_000);
      second
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
                  Http2TestFrames.frame(FrameType.PING, 0, 0, opaque)));
      second.getOutputStream().flush();

      Http2TestFrames.WireFrame pong = null;
      for (int i = 0; i < 6; i++) {
        Http2TestFrames.WireFrame frame = readFrame(second.getInputStream());
        if (frame.type() == FrameType.PING.code()
            && (frame.flags() & FrameFlags.ACK) != 0
            && Arrays.equals(opaque, frame.payload())) {
          pong = frame;
          break;
        }
      }
      assertNotNull(pong, "a fresh connection must start with fresh SETTINGS/GOAWAY state");
    }
  }

  @Test
  void tlsListenerOffersAndNegotiatesH2WhenHttp2IsEnabled(@TempDir Path directory)
      throws Exception {
    int port = freePort();
    Path keystore =
        TestKeystores.build(
            directory,
            "http2.p12",
            "changeit",
            TestKeystores.Entry.of("server", "localhost", "localhost"));
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .tls(TlsConfig.keystore(keystore, "changeit"))
                .http2Enabled(true)
                .build());
    app.start();

    try (SSLSocket socket =
        (SSLSocket)
            TestKeystores.trustAllClientContext()
                .getSocketFactory()
                .createSocket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      SSLParameters parameters = socket.getSSLParameters();
      parameters.setApplicationProtocols(new String[] {"h2", "http/1.1"});
      socket.setSSLParameters(parameters);
      socket.startHandshake();
      assertEquals("h2", socket.getApplicationProtocol());

      socket
          .getOutputStream()
          .write(Http2TestFrames.concat(Http2TestFrames.PREFACE, Http2TestFrames.settings()));
      socket.getOutputStream().flush();
      assertEquals(FrameType.SETTINGS.code(), readFrame(socket.getInputStream()).type());
    }
  }

  private static Http2TestFrames.WireFrame readUntil(Socket socket, FrameType type)
      throws Exception {
    for (int i = 0; i < 8; i++) {
      Http2TestFrames.WireFrame frame = readFrame(socket.getInputStream());
      if (frame.type() == type.code()) return frame;
    }
    fail("did not receive " + type);
    throw new AssertionError();
  }

  private static long verifyPattern(InputStream input) throws Exception {
    byte[] buffer = new byte[64 * 1024];
    long position = 0;
    int count;
    while ((count = input.read(buffer)) >= 0) {
      for (int i = 0; i < count; i++) assertEquals((byte) (position++ * 31), buffer[i]);
    }
    return position;
  }

  private static final class PatternInputStream extends InputStream {
    private final long length;
    private long position;

    PatternInputStream(long length) {
      this.length = length;
    }

    @Override
    public int read() {
      if (position == length) return -1;
      return (byte) (position++ * 31) & 0xff;
    }

    @Override
    public int read(byte[] target, int offset, int requested) {
      if (position == length) return -1;
      int count = (int) Math.min(requested, length - position);
      for (int i = 0; i < count; i++) target[offset + i] = (byte) (position++ * 31);
      return count;
    }
  }

  private static Http2TestFrames.WireFrame readFrame(InputStream input) throws Exception {
    byte[] header = input.readNBytes(9);
    if (header.length != 9) throw new EOFException("truncated frame header");
    int length = ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
    byte[] payload = input.readNBytes(length);
    if (payload.length != length) throw new EOFException("truncated frame payload");
    return new Http2TestFrames.WireFrame(
        header[3] & 0xff,
        header[4] & 0xff,
        Http2TestFrames.readInt(header, 5) & Integer.MAX_VALUE,
        payload);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static String ascii(dev.relism.fpr.core.ByteView view) {
    byte[] bytes = new byte[view.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = view.byteAt(i);
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}
