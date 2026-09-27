package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http.ContentType;
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

class Http2ConnectTest {
  private App app;
  private Server server;

  @AfterEach
  void stop() {
    if (server != null) server.stop().join();
  }

  @Test
  void connectTunnelCanExchangeDataBeforeEitherSideCloses() throws Exception {
    ServerConfig config1 = ServerConfig.builder()
                .host("127.0.0.1")
                .port(0)
                .http2CleartextEnabled(true)
                .build();
    app = App.create();
    app.route(HttpMethod.CONNECT, "tunnel", (request, response) ->
        response.type(ContentType.NONE).streaming(output -> {
          byte[] bytes = new byte[16];
          try {
            int count;
            InputStream input = request.body().stream();
            while ((count = input.read(bytes)) >= 0) {
              output.write(bytes, 0, count);
              output.flush();
            }
          } catch (Exception failure) {
            throw new RuntimeException(failure);
          }
        }));
    server = Server.start(app, config1);
    int port = server.port();

    ByteWriter block = new ByteWriter(32);
    HpackEncoder.writeLiteralWithNameIndex(block, 2, ascii("CONNECT"), false);
    HpackEncoder.writeLiteralWithNameIndex(block, 1, ascii("tunnel"), false);

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(5_000);
      socket.getOutputStream().write(Http2TestFrames.concat(
          Http2TestFrames.PREFACE, Http2TestFrames.settings(),
          Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
          Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1,
              Arrays.copyOf(block.array(), block.length())),
          Http2TestFrames.frame(FrameType.DATA, 0, 1, ascii("one"))));
      socket.getOutputStream().flush();

      assertEquals("one", new String(readData(socket.getInputStream()).payload(),
          StandardCharsets.US_ASCII));

      socket.getOutputStream().write(Http2TestFrames.frame(
          FrameType.DATA, FrameFlags.END_STREAM, 1, ascii("two")));
      socket.getOutputStream().flush();
      assertEquals("two", new String(readData(socket.getInputStream()).payload(),
          StandardCharsets.US_ASCII));
    }
  }

  private static Http2TestFrames.WireFrame readData(InputStream input) throws Exception {
    for (int i = 0; i < 12; i++) {
      Http2TestFrames.WireFrame frame = readFrame(input);
      if (frame.streamId() == 1 && frame.type() == FrameType.DATA.code()
          && frame.payload().length != 0) return frame;
    }
    throw new AssertionError("missing tunnel DATA");
  }

  private static Http2TestFrames.WireFrame readFrame(InputStream input) throws Exception {
    byte[] header = input.readNBytes(9);
    if (header.length != 9) throw new EOFException();
    int length = ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
    byte[] payload = input.readNBytes(length);
    return new Http2TestFrames.WireFrame(
        header[3] & 0xff, header[4] & 0xff, Http2TestFrames.readInt(header, 5) & 0x7fff_ffff,
        payload);
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

}
