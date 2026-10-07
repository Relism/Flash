package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.DuplexHandler;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
    app.route(HttpMethod.CONNECT, "tunnel", new DuplexHandler() {
      @Override protected void prepare(Request request, Response response) {
        response.type(ContentType.NONE);
      }

      @Override protected void handle(Request request, Response response, Duplex duplex) throws Exception {
        int count;
        while ((count = duplex.fill()) >= 0) { // echoes in place, straight from the DATA buffers
          duplex.write(duplex.array(), duplex.offset(), count);
          duplex.flush();
          duplex.advance(count);
        }
      }
    });
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

  @Test
  void aResetFreesTheWritingHandlerAndALateFailureResetsOnlyItsStream() throws Exception {
    CompletableFuture<Throwable> stopped = new CompletableFuture<>();
    app = App.create();
    app.get("/endless", (request, response) -> response.streaming(out -> {
      byte[] chunk = new byte[1024];
      try {
        while (true) out.write(chunk, 0, chunk.length);
      } catch (IOException reset) {
        stopped.complete(reset);
        throw reset;
      }
    }));
    app.get("/broken", (request, response) -> response.streaming(out -> {
      out.flush();
      throw new IllegalStateException("broken after the headers left");
    }));

    app.get("/after", (request, response) -> "after");
    server = Server.start(app, ServerConfig.builder()
        .host("127.0.0.1").port(0).http2CleartextEnabled(true).build());

    try (Socket socket = new Socket("127.0.0.1", server.port())) {
      socket.setSoTimeout(5_000);
      InputStream input = socket.getInputStream();
      socket.getOutputStream().write(Http2TestFrames.concat(
          Http2TestFrames.PREFACE, Http2TestFrames.settings(),
          Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
          // the connection window outlives the stream /endless fills, so the later answers need room
          Http2TestFrames.frame(FrameType.WINDOW_UPDATE, 0, 0, new byte[] {0x40, 0, 0, 0}),
          get(1, "/endless")));
      readData(input);
      socket.getOutputStream().write(Http2TestFrames.frame(
          FrameType.RST_STREAM, 0, 1, new byte[] {0, 0, 0, 8}));
      assertInstanceOf(IOException.class, stopped.get(5, TimeUnit.SECONDS));

      socket.getOutputStream().write(get(3, "/broken"));
      Http2TestFrames.WireFrame reset = next(input, 3, FrameType.RST_STREAM);
      assertEquals(2, Http2TestFrames.readInt(reset.payload(), 0)); // INTERNAL_ERROR

      socket.getOutputStream().write(get(5, "/after"));
      assertEquals("after", new String(next(input, 5, FrameType.DATA).payload(), StandardCharsets.US_ASCII));
    }
  }

  private static byte[] get(int streamId, String path) {
    ByteWriter block = new ByteWriter(64);
    HpackEncoder.writeLiteralWithNameIndex(block, 2, ascii("GET"), false);
    HpackEncoder.writeLiteralWithNameIndex(block, 6, ascii("http"), false);
    HpackEncoder.writeLiteralWithNameIndex(block, 4, ascii(path), false);
    HpackEncoder.writeLiteralWithNameIndex(block, 1, ascii("localhost"), false);
    return Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS | FrameFlags.END_STREAM, streamId,
        Arrays.copyOf(block.array(), block.length()));
  }

  private static Http2TestFrames.WireFrame next(InputStream input, int streamId, FrameType type) throws Exception {
    while (true) {
      Http2TestFrames.WireFrame frame = readFrame(input);
      if (frame.streamId() == streamId && frame.type() == type.code()) return frame;
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
