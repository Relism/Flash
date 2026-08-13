package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http2.frame.FrameType;
import java.io.EOFException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Http2RegressionCorpusTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @ParameterizedTest
  @CsvSource({
    "invalid-preface.hex,GOAWAY,PROTOCOL_ERROR",
    "lower-unopened-stream.hex,GOAWAY,PROTOCOL_ERROR"
  })
  void exactWireCorpusProducesRequiredProtocolOutcome(
      String resource, String expectedFrame, String expectedError) throws Exception {
    byte[] input = load(resource);
    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(Http2ConnectionHandshakeTest.run(input).output());
    Http2TestFrames.WireFrame terminal = frames.get(frames.size() - 1);

    FrameType type = FrameType.valueOf(expectedFrame);
    assertEquals(type.code(), terminal.type());
    int errorOffset = type == FrameType.GOAWAY ? 4 : 0;
    assertEquals(
        Http2ErrorCode.valueOf(expectedError).code(),
        Http2TestFrames.readInt(terminal.payload(), errorOffset));
  }

  @Test
  void headersOnHalfClosedRemoteStreamUseStreamErrorBeforeDispatch() throws Exception {
    int port = freePort();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(port)
                .http2CleartextEnabled(true)
                .build());
    app.get("/", (request, response) -> "ok");
    app.start();

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      socket.getOutputStream().write(load("headers-after-end-stream.hex"));
      socket.getOutputStream().flush();
      for (int i = 0; i < 10; i++) {
        Http2TestFrames.WireFrame frame = readFrame(socket.getInputStream());
        if (frame.type() != FrameType.RST_STREAM.code()) continue;
        assertEquals(1, frame.streamId());
        assertEquals(Http2ErrorCode.STREAM_CLOSED.code(), Http2TestFrames.readInt(frame.payload(), 0));
        return;
      }
      throw new AssertionError("missing RST_STREAM(STREAM_CLOSED)");
    }
  }

  private static byte[] load(String name) throws Exception {
    String path = "/http2/regressions/" + name;
    try (InputStream input = Http2RegressionCorpusTest.class.getResourceAsStream(path)) {
      if (input == null) throw new AssertionError("missing regression resource " + path);
      String text = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
      StringBuilder hex = new StringBuilder();
      for (String line : text.split("\\R")) {
        String data = line.strip();
        if (!data.isEmpty() && !data.startsWith("#")) hex.append(data);
      }
      return HexFormat.of().parseHex(hex);
    }
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

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
