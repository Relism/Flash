package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.hpack.HeaderListSizeException;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;
import dev.relism.flash.http2.message.PseudoHeaders;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.transport.BufferedByteSource;
import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.InputStream;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Http2AbuseTest {
  @Test
  void rapidResetClosesConnectionWithEnhanceYourCalm() throws Exception {
    List<byte[]> frames = new ArrayList<>();
    frames.add(Http2TestFrames.PREFACE);
    frames.add(Http2TestFrames.settings());
    for (int i = 0; i <= Http2Limits.MAX_RESET_STREAMS_PER_INTERVAL; i++) {
      int streamId = i * 2 + 1;
      frames.add(Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, streamId,
          new byte[0]));
      frames.add(Http2TestFrames.frame(FrameType.RST_STREAM, 0, streamId, new byte[4]));
    }
    assertCalm(run(frames));
  }

  @Test
  void streamCreationFloodIsBoundedIndependentlyOfResets() throws Exception {
    List<byte[]> frames = new ArrayList<>();
    frames.add(Http2TestFrames.PREFACE);
    frames.add(Http2TestFrames.settings());
    for (int i = 0; i <= Http2Limits.MAX_STREAMS_CREATED_PER_INTERVAL; i++) {
      frames.add(Http2TestFrames.frame(
          FrameType.HEADERS, FrameFlags.END_HEADERS, i * 2 + 1, new byte[0]));
    }
    assertCalm(run(frames));
  }

  @Test
  void settingsAndPingFloodsAreRateLimited() throws Exception {
    List<byte[]> settings = base();
    for (int i = 0; i <= Http2Limits.MAX_SETTINGS_PER_INTERVAL; i++) {
      settings.add(Http2TestFrames.settings());
    }
    assertCalm(run(settings));

    List<byte[]> pings = base();
    for (int i = 0; i <= Http2Limits.MAX_PINGS_PER_INTERVAL; i++) {
      pings.add(Http2TestFrames.frame(FrameType.PING, 0, 0, new byte[8]));
    }
    assertCalm(run(pings));
  }

  @Test
  void aggregateNonProgressFrameFloodIsRateLimited() throws Exception {
    List<byte[]> frames = base();
    byte[] priority = new byte[5];
    for (int i = 0; i <= Http2Limits.MAX_USELESS_FRAMES_PER_INTERVAL; i++) {
      frames.add(Http2TestFrames.frame(FrameType.PRIORITY, 0, 1, priority));
    }
    assertCalm(run(frames));
  }

  @Test
  void operatorConnectionStreamAndByteBudgetsAreEnforced() throws Exception {
    ServerConfig oneStream = ServerConfig.builder()
        .h2MaxStreamsPerConnection(1).build();
    List<byte[]> streams = base();
    streams.add(Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1, new byte[0]));
    streams.add(Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 3, new byte[0]));
    assertCalm(runConfigured(streams, oneStream));

    ServerConfig nineBytes = ServerConfig.builder()
        .h2MaxBytesPerConnection(9).build();
    List<byte[]> bytes = base();
    bytes.add(Http2TestFrames.frame(FrameType.PING, 0, 0, new byte[8]));
    assertCalm(runConfigured(bytes, nineBytes));
  }

  @Test
  void optionalConnectionLifetimeBudgetRotatesTheConnection() throws Exception {
    byte[] initial = Http2TestFrames.concat(Http2TestFrames.PREFACE, Http2TestFrames.settings());
    ByteArrayInputStream delegate = new ByteArrayInputStream(initial);
    InputStream stalled = new InputStream() {
      @Override
      public int read(byte[] target, int offset, int length) throws IOException {
        if (delegate.available() > 0) return delegate.read(target, offset, length);
        try {
          Thread.sleep(5);
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException(interrupted);
        }
        throw new SocketTimeoutException("idle");
      }

      @Override
      public int read() throws IOException {
        byte[] one = new byte[1];
        int count = read(one, 0, 1);
        return count < 0 ? -1 : one[0] & 0xff;
      }
    };
    Http2Connection connection = new Http2Connection();
    connection.configure(ServerConfig.builder().h2MaxConnectionLifetimeMs(1).build());
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Http2FrameWriter writer = new Http2FrameWriter(output::write, 5_000);
    try {
      connection.run(new BufferedByteSource(stalled, null), writer, () -> false);
    } finally {
      writer.close();
    }

    assertCalm(new Run(Http2TestFrames.parse(output.toByteArray())));
  }

  @Test
  void continuationFloodDiesBeforeMaterializingAttack() throws Exception {
    List<byte[]> frames = base();
    frames.add(Http2TestFrames.frame(FrameType.HEADERS, 0, 1, new byte[] {(byte) 0x82}));
    byte[] continuation = Http2TestFrames.frame(FrameType.CONTINUATION, 0, 1, new byte[0]);
    for (int i = 0; i < 100_000; i++) frames.add(continuation);
    byte[] input = Http2TestFrames.concat(frames.toArray(byte[][]::new));
    long before = usedHeap();

    Run result = org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
        Duration.ofSeconds(2), () -> run(input));

    assertEquals(Http2ErrorCode.PROTOCOL_ERROR.code(), result.lastGoAwayError());
    assertTrue(usedHeap() - before < 8L * 1024 * 1024, "attack processing retained too much heap");
  }

  @Test
  void hpackBombStopsPublishingFieldsAtTheConfiguredBound() {
    ByteWriter block = new ByteWriter(4096);
    byte[] name = "x".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] value = new byte[1024];
    for (int i = 0; i < 100; i++) HpackEncoder.writeLiteral(block, name, value);
    int[] published = {0};

    assertThrows(
        HeaderListSizeException.class,
        () -> new HpackDecoder(4096, 4096).decode(
            block.array(), 0, block.length(), (n, v, sensitive) -> published[0]++));

    assertTrue(published[0] <= 3, "fields beyond the list bound reached stream storage");
  }

  @Test
  void incompleteHeaderBlockHasAnAbsoluteAssemblyDeadline() throws Exception {
    byte[] wire = Http2TestFrames.frame(FrameType.HEADERS, 0, 1, new byte[] {(byte) 0x82});
    dev.relism.flash.http2.frame.Http2FrameReader reader =
        new dev.relism.flash.http2.frame.Http2FrameReader(
            new BufferedByteSource(new ByteArrayInputStream(wire), null));
    Http2HeaderBlockDecoder decoder = new Http2HeaderBlockDecoder(1);
    decoder.accept(reader.readFrame(), (name, value, sensitive) -> {});
    Thread.sleep(5);

    Http2Exception failure = assertThrows(Http2Exception.class, decoder::checkTimeout);
    assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, failure.errorCode());
  }

  @Test
  void idleOpenStreamIsCancelledWithinConfiguredDeadline() throws Exception {
    ServerConfig config1 = ServerConfig.builder()
                .host("127.0.0.1")
                .port(0)
                .http2CleartextEnabled(true)
                .h2StreamIdleTimeoutMs(20)
                .build();
    App app = App.create();
    app.post("/idle", (request, response) -> request.body().bytes());
    Server server = Server.start(app, config1);
    int port = server.port();
    ByteWriter headers = new ByteWriter(64);
    HpackEncoder.writeIndexed(headers, 3);
    HpackEncoder.writeIndexed(headers, 6);
    HpackEncoder.writeLiteralWithNameIndex(headers, 4, ascii("/idle"), false);
    HpackEncoder.writeLiteralWithNameIndex(headers, 1, ascii("localhost"), false);

    try (Socket socket = new Socket("127.0.0.1", port)) {
      socket.setSoTimeout(2_000);
      socket.getOutputStream().write(Http2TestFrames.concat(
          Http2TestFrames.PREFACE, Http2TestFrames.settings(),
          Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]),
          Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1,
              java.util.Arrays.copyOf(headers.array(), headers.length()))));
      socket.getOutputStream().flush();
      Http2TestFrames.WireFrame rst = readUntil(socket.getInputStream(), FrameType.RST_STREAM);
      assertEquals(Http2ErrorCode.CANCEL.code(), Http2TestFrames.readInt(rst.payload(), 0));
    } finally {
      server.stop().join();
    }
  }

  @Test
  void zeroNameDuplicatePseudoAndOversizedFieldAreRejected() {
    HpackHeaderBlock emptyName = new HpackHeaderBlock();
    new HpackDecoder().decode(new byte[] {0, 0, 0}, 0, 3, emptyName);
    assertThrows(Http2StreamException.class,
        () -> new PseudoHeaders().validate(emptyName, 1));

    HpackHeaderBlock duplicate = new HpackHeaderBlock();
    new HpackDecoder().decode(new byte[] {(byte) 0x82, (byte) 0x82}, 0, 2, duplicate);
    assertThrows(Http2StreamException.class,
        () -> new PseudoHeaders().validate(duplicate, 1));

    assertThrows(Http2Exception.class,
        () -> new HpackDecoder().decode(new byte[] {0, 0x7f, (byte) 0x81, 0x3f}, 0, 4,
            (n, v, s) -> {}));
  }

  private static List<byte[]> base() {
    List<byte[]> frames = new ArrayList<>();
    frames.add(Http2TestFrames.PREFACE);
    frames.add(Http2TestFrames.settings());
    return frames;
  }

  private static Run run(List<byte[]> frames) throws Exception {
    return run(Http2TestFrames.concat(frames.toArray(byte[][]::new)));
  }

  private static Run run(byte[] input) throws Exception {
    Http2ConnectionHandshakeTest.RunResult result = Http2ConnectionHandshakeTest.run(input);
    return new Run(Http2TestFrames.parse(result.output()));
  }

  private static Run runConfigured(List<byte[]> frames, ServerConfig configuration)
      throws Exception {
    Http2Connection connection = new Http2Connection();
    connection.configure(configuration);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Http2FrameWriter writer = new Http2FrameWriter(output::write, 5_000);
    try {
      connection.run(
          new BufferedByteSource(
              new ByteArrayInputStream(Http2TestFrames.concat(frames.toArray(byte[][]::new))), null),
          writer,
          () -> false);
      writer.drain();
    } finally {
      writer.close();
    }
    return new Run(Http2TestFrames.parse(output.toByteArray()));
  }

  private static void assertCalm(Run result) {
    assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM.code(), result.lastGoAwayError());
  }

  private static long usedHeap() {
    Runtime runtime = Runtime.getRuntime();
    return runtime.totalMemory() - runtime.freeMemory();
  }

  private static byte[] ascii(String text) {
    return text.getBytes(StandardCharsets.US_ASCII);
  }

  private static Http2TestFrames.WireFrame readUntil(InputStream input, FrameType expected)
      throws Exception {
    for (int i = 0; i < 12; i++) {
      byte[] header = input.readNBytes(9);
      if (header.length != 9) throw new EOFException();
      int length = ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
      byte[] payload = input.readNBytes(length);
      Http2TestFrames.WireFrame frame = new Http2TestFrames.WireFrame(
          header[3] & 0xff, header[4] & 0xff,
          Http2TestFrames.readInt(header, 5) & 0x7fff_ffff, payload);
      if (frame.type() == expected.code()) return frame;
    }
    throw new AssertionError("missing " + expected);
  }


  private record Run(List<Http2TestFrames.WireFrame> frames) {
    int lastGoAwayError() {
      for (int i = frames.size() - 1; i >= 0; i--) {
        Http2TestFrames.WireFrame frame = frames.get(i);
        if (frame.type() == FrameType.GOAWAY.code()) {
          return Http2TestFrames.readInt(frame.payload(), 4);
        }
      }
      throw new AssertionError("missing GOAWAY");
    }
  }
}
