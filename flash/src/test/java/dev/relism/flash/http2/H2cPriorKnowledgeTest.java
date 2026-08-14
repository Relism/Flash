package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
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

    try (Socket socket = new Socket("127.0.0.1", enabledPort)) {
      socket.setSoTimeout(5_000);
      ByteWriter block = new ByteWriter(32);
      HpackEncoder.writeIndexed(block, 2); // :method GET
      HpackEncoder.writeIndexed(block, 6); // :scheme http
      HpackEncoder.writeIndexed(block, 4); // :path /
      HpackEncoder.writeLiteralWithNameIndex(
          block, 1, ("127.0.0.1:" + enabledPort).getBytes(StandardCharsets.US_ASCII), false);
      socket
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(
                      FrameType.HEADERS,
                      FrameFlags.END_HEADERS | FrameFlags.END_STREAM,
                      1,
                      Arrays.copyOf(block.array(), block.length()))));
      socket.getOutputStream().flush();

      assertEquals(200, readStatus(socket.getInputStream()));
      assertEquals("h2c", new String(readData(socket.getInputStream()), StandardCharsets.UTF_8));
    }
  }

  private static int readStatus(InputStream input) throws Exception {
    HpackDecoder decoder = new HpackDecoder();
    while (true) {
      Http2TestFrames.WireFrame frame = readFrame(input);
      if (frame.type() != FrameType.HEADERS.code() || frame.streamId() != 1) continue;
      int[] status = {0};
      decoder.decode(
          frame.payload(),
          0,
          frame.payload().length,
          (name, value, never) -> {
            if (name.length() == 7 && name.byteAt(0) == ':') {
              status[0] =
                  (value.byteAt(0) - '0') * 100
                      + (value.byteAt(1) - '0') * 10
                      + value.byteAt(2)
                      - '0';
            }
          });
      return status[0];
    }
  }

  private static byte[] readData(InputStream input) throws Exception {
    for (int i = 0; i < 12; i++) {
      Http2TestFrames.WireFrame frame = readFrame(input);
      if (frame.streamId() == 1 && frame.type() == FrameType.DATA.code()
          && frame.payload().length != 0) return frame.payload();
    }
    throw new AssertionError("missing h2c response DATA");
  }

  private static Http2TestFrames.WireFrame readFrame(InputStream input) throws Exception {
    byte[] header = input.readNBytes(9);
    int length = ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
    byte[] payload = input.readNBytes(length);
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
