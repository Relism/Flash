package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
}
