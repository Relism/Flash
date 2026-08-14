package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Http2TrailersTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void requestTrailersReachHandlerAfterBodyEof() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    app.post("/trailers", (request, response) -> {
      assertEquals("abc", new String(request.body().bytes(), StandardCharsets.US_ASCII));
      return request.trailers().first("grpc-status");
    });
    app.start();

    byte[] initial = requestHeaders("/trailers");
    ByteWriter trailer = new ByteWriter(32);
    HpackEncoder.writeLiteral(
        trailer, "grpc-status".getBytes(StandardCharsets.US_ASCII),
        "7".getBytes(StandardCharsets.US_ASCII));

    try (Socket socket = connect(port)) {
      socket.getOutputStream().write(Http2TestFrames.concat(
          Http2TestFrames.PREFACE,
          Http2TestFrames.settings(),
          Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
          Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1, initial),
          Http2TestFrames.frame(FrameType.DATA, 0, 1, "abc".getBytes(StandardCharsets.US_ASCII)),
          Http2TestFrames.frame(FrameType.HEADERS,
              FrameFlags.END_HEADERS | FrameFlags.END_STREAM, 1,
              Arrays.copyOf(trailer.array(), trailer.length()))));
      socket.getOutputStream().flush();

      Http2TestFrames.WireFrame data = frameOfType(socket.getInputStream(), 1, FrameType.DATA);
      assertEquals("7", new String(data.payload(), StandardCharsets.US_ASCII));
    }
  }

  @Test
  void trailersWithoutEndStreamAreRejected() throws Exception {
    int port = startBlockingRoute();
    ByteWriter trailer = new ByteWriter(32);
    HpackEncoder.writeLiteral(trailer, ascii("x-end"), ascii("no"));
    try (Socket socket = connect(port)) {
      socket.getOutputStream().write(Http2TestFrames.concat(
          Http2TestFrames.PREFACE, Http2TestFrames.settings(),
          Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
          Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1, requestHeaders("/trailers")),
          Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1,
              Arrays.copyOf(trailer.array(), trailer.length()))));
      socket.getOutputStream().flush();
      Http2TestFrames.WireFrame rst = frameOfType(socket.getInputStream(), 1, FrameType.RST_STREAM);
      assertEquals(Http2ErrorCode.PROTOCOL_ERROR.code(), Http2TestFrames.readInt(rst.payload(), 0));
    }
  }

  @Test
  void pseudoHeaderInTrailersIsRejected() throws Exception {
    int port = startBlockingRoute();
    try (Socket socket = connect(port)) {
      socket.getOutputStream().write(Http2TestFrames.concat(
          Http2TestFrames.PREFACE, Http2TestFrames.settings(),
          Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
          Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1, requestHeaders("/trailers")),
          Http2TestFrames.frame(FrameType.HEADERS,
              FrameFlags.END_HEADERS | FrameFlags.END_STREAM, 1, new byte[] {(byte) 0x88})));
      socket.getOutputStream().flush();
      Http2TestFrames.WireFrame rst = frameOfType(socket.getInputStream(), 1, FrameType.RST_STREAM);
      assertEquals(Http2ErrorCode.PROTOCOL_ERROR.code(), Http2TestFrames.readInt(rst.payload(), 0));
    }
  }

  private int startBlockingRoute() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    app.post("/trailers", (request, response) -> request.body().bytes());
    app.start();
    return port;
  }

  private static byte[] requestHeaders(String path) {
    ByteWriter block = new ByteWriter(64);
    HpackEncoder.writeIndexed(block, 3);
    HpackEncoder.writeIndexed(block, 6);
    HpackEncoder.writeLiteralWithNameIndex(block, 4, ascii(path), false);
    HpackEncoder.writeLiteralWithNameIndex(block, 1, ascii("localhost"), false);
    HpackEncoder.writeLiteralWithNameIndex(block, 59, ascii("trailers"), false);
    return Arrays.copyOf(block.array(), block.length());
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static Socket connect(int port) throws Exception {
    Socket socket = new Socket("127.0.0.1", port);
    socket.setSoTimeout(5_000);
    return socket;
  }

  private static Http2TestFrames.WireFrame frameOfType(
      InputStream input, int streamId, FrameType type) throws Exception {
    for (int i = 0; i < 12; i++) {
      Http2TestFrames.WireFrame frame = readFrame(input);
      if (frame.streamId() == streamId && frame.type() == type.code()) return frame;
    }
    throw new AssertionError("missing " + type + " frame");
  }

  private static Http2TestFrames.WireFrame readFrame(InputStream input) throws Exception {
    byte[] header = input.readNBytes(9);
    if (header.length != 9) throw new EOFException();
    int length = ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
    byte[] payload = input.readNBytes(length);
    if (payload.length != length) throw new EOFException();
    return new Http2TestFrames.WireFrame(
        header[3] & 0xff, header[4] & 0xff, Http2TestFrames.readInt(header, 5) & 0x7fff_ffff,
        payload);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
