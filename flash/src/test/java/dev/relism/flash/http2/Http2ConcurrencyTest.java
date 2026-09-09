package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class Http2ConcurrencyTest {
  private FlashApp app;

  @AfterEach
  void stop() {
    if (app != null) app.stop().join();
  }

  @Test
  void thousandMultiplexedStreamsCompleteOnOneConnection() throws Exception {
    AtomicInteger handled = new AtomicInteger();
    app =
        FlashApp.create(
            FlashConfiguration.builder()
                .host("127.0.0.1")
                .port(0)
                .http2CleartextEnabled(true)
                .h2MaxStreamsCreatedPerInterval(2_000)
                .build());
    int port = app.port();
    app.get("/work", (request, response) -> Integer.toString(handled.incrementAndGet()));
    app.start();

    byte[] headers = requestHeaders();
    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(10_000);
      socket
          .getOutputStream()
          .write(
              Http2TestFrames.concat(
                  Http2TestFrames.PREFACE,
                  Http2TestFrames.settings(),
                  Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0])));

      int sent = 0;
      while (sent < 1_000) {
        int batch = Math.min(Http2Limits.MAX_CONCURRENT_STREAMS, 1_000 - sent);
        for (int i = 0; i < batch; i++) {
          int streamId = (sent + i) * 2 + 1;
          socket
              .getOutputStream()
              .write(
                  Http2TestFrames.frame(
                      FrameType.HEADERS,
                      FrameFlags.END_HEADERS | FrameFlags.END_STREAM,
                      streamId,
                      headers));
        }
        socket.getOutputStream().flush();

        int completed = 0;
        while (completed < batch) {
          Http2TestFrames.WireFrame frame = readFrame(socket.getInputStream());
          if (frame.type() == FrameType.GOAWAY.code()
              || frame.type() == FrameType.RST_STREAM.code()) {
            fail("server rejected stream " + frame.streamId() + " with frame " + frame.type());
          }
          if (frame.streamId() != 0 && (frame.flags() & FrameFlags.END_STREAM) != 0) completed++;
        }
        sent += batch;
      }
    }

    assertEquals(1_000, handled.get());
  }

  private static byte[] requestHeaders() {
    ByteWriter block = new ByteWriter(64);
    HpackEncoder.writeIndexed(block, 2);
    HpackEncoder.writeIndexed(block, 6);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 4, "/work".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        block, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);
    return Arrays.copyOf(block.array(), block.length());
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
