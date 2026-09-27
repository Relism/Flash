package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.testing.FuzzMemory;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@Tag("nightly")
@EnabledIfSystemProperty(named = "flash.http2.soak", matches = "true")
class Http2SoakTest {
  private App app;
  private Server server;

  @AfterEach
  void stop() {
    if (server != null) server.stop().join();
  }

  @Test
  void sustainedMixedTrafficRetainsBoundedHeapAndCompletesRequests() throws Exception {
    long seconds = Long.getLong("flash.http2.soak.seconds", 600L);
    byte[] streamBody = new byte[8 * 1024];
    Arrays.fill(streamBody, (byte) 's');
    ServerConfig config1 = ServerConfig.builder()
                .host("127.0.0.1")
                .port(0)
                .http2CleartextEnabled(true)
                .h2MaxStreamsCreatedPerInterval(100_000)
                .h2MaxStreamsPerConnection(0)
                .build();
    app = App.create();
    app.get("/get", (request, response) -> "get");
    app.post("/post", (request, response) -> request.body().bytes());
    app.get("/stream", (request, response) -> response.chunked(new ByteArrayInputStream(streamBody)));
    server = Server.start(app, config1);
    int port = server.port();

    long baseline = FuzzMemory.snapshot();
    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    int completed = 0;
    int streamId = 1;
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      socket
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0])));
      socket.getOutputStream().flush();

      for (int operation = 0; System.nanoTime() < deadline; operation++, streamId += 2) {
        int kind = operation % 5;
        if (kind == 3) {
          byte[] opaque = new byte[8];
          opaque[7] = (byte) operation;
          socket.getOutputStream().write(Http2TestFrames.frame(FrameType.PING, 0, 0, opaque));
          socket.getOutputStream().flush();
          awaitPing(socket.getInputStream());
          continue;
        }
        if (kind == 4) {
          socket
              .getOutputStream()
              .write(
                  Http2TestFrames.concat(
                      Http2TestFrames.frame(
                          FrameType.HEADERS,
                          FrameFlags.END_HEADERS,
                          streamId,
                          requestHeaders("/post", false)),
                      Http2TestFrames.frame(
                          FrameType.RST_STREAM,
                          0,
                          streamId,
                          Http2ErrorCode.CANCEL.bytes())));
          socket.getOutputStream().flush();
          continue;
        }

        boolean post = kind == 1;
        String path = kind == 2 ? "/stream" : (post ? "/post" : "/get");
        byte[] head =
            Http2TestFrames.frame(
                FrameType.HEADERS,
                FrameFlags.END_HEADERS | (post ? 0 : FrameFlags.END_STREAM),
                streamId,
                requestHeaders(path, post));
        if (post) {
          byte[] data = ("body-" + operation).getBytes(StandardCharsets.US_ASCII);
          socket
              .getOutputStream()
              .write(
                  Http2TestFrames.concat(
                      head,
                      Http2TestFrames.frame(
                          FrameType.DATA, FrameFlags.END_STREAM, streamId, data)));
        } else {
          socket.getOutputStream().write(head);
        }
        socket.getOutputStream().flush();
        awaitResponse(socket, streamId);
        completed++;
      }
    }

    assertTrue(completed > 0);
    FuzzMemory.assertGrowthBelow(baseline, 32L * 1024 * 1024);
  }

  private static byte[] requestHeaders(String path, boolean post) {
    ByteWriter block = new ByteWriter(64);
    HpackEncoder.writeIndexed(block, post ? 3 : 2);
    HpackEncoder.writeIndexed(block, 6);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 4, path.getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);
    return Arrays.copyOf(block.array(), block.length());
  }

  private static void awaitResponse(Socket socket, int streamId) throws Exception {
    while (true) {
      Http2TestFrames.WireFrame frame = readFrame(socket.getInputStream());
      if (frame.type() == FrameType.GOAWAY.code()) {
        throw new AssertionError("unexpected GOAWAY during soak");
      }
      if (frame.type() == FrameType.DATA.code() && frame.payload().length > 0) {
        byte[] increment = intBytes(frame.payload().length);
        socket
            .getOutputStream()
            .write(Http2TestFrames.frame(FrameType.WINDOW_UPDATE, 0, 0, increment));
        socket
            .getOutputStream()
            .write(Http2TestFrames.frame(FrameType.WINDOW_UPDATE, 0, streamId, increment));
        socket.getOutputStream().flush();
      }
      if (frame.streamId() == streamId && (frame.flags() & FrameFlags.END_STREAM) != 0) return;
    }
  }

  private static void awaitPing(InputStream input) throws Exception {
    while (true) {
      Http2TestFrames.WireFrame frame = readFrame(input);
      if (frame.type() == FrameType.PING.code() && (frame.flags() & FrameFlags.ACK) != 0) return;
    }
  }

  private static byte[] intBytes(int value) {
    return new byte[] {(byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value};
  }

  private static Http2TestFrames.WireFrame readFrame(InputStream input) throws Exception {
    byte[] header = input.readNBytes(9);
    if (header.length != 9) throw new EOFException("truncated frame header");
    int length =
        ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
    byte[] payload = input.readNBytes(length);
    if (payload.length != length) throw new EOFException("truncated frame payload");
    return new Http2TestFrames.WireFrame(
        header[3] & 0xff,
        header[4] & 0xff,
        Http2TestFrames.readInt(header, 5) & Integer.MAX_VALUE,
        payload);
  }

}
