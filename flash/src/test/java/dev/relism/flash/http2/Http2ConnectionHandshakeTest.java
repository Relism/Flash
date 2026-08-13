package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.transport.BufferedByteSource;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.Test;

class Http2ConnectionHandshakeTest {
  @Test
  void exactPrefaceExchangesSettingsAndAcknowledgesPeerSettings() throws Exception {
    byte[] input =
        Http2TestFrames.concat(
            Http2TestFrames.PREFACE,
            Http2TestFrames.settings(Http2Settings.MAX_FRAME_SIZE, 32_768));

    RunResult result = run(input);
    List<Http2TestFrames.WireFrame> frames = Http2TestFrames.parse(result.output());

    assertEquals(3, frames.size());
    assertEquals(FrameType.SETTINGS.code(), frames.get(0).type());
    assertEquals(0, frames.get(0).flags());
    assertEquals(FrameType.WINDOW_UPDATE.code(), frames.get(1).type());
    assertEquals(FrameType.SETTINGS.code(), frames.get(2).type());
    assertEquals(FrameFlags.ACK, frames.get(2).flags());
    assertEquals(0, frames.get(2).payload().length);
    assertEquals(32_768, result.connection().peerSettings().maxFrameSize());
  }

  @Test
  void mismatchedPrefaceSendsProtocolErrorButTruncatedPrefaceClosesSilently() throws Exception {
    byte[] mismatched = Http2TestFrames.PREFACE.clone();
    mismatched[10] ^= 1;

    List<Http2TestFrames.WireFrame> frames = Http2TestFrames.parse(run(mismatched).output());
    assertEquals(1, frames.size());
    assertEquals(FrameType.GOAWAY.code(), frames.get(0).type());
    assertEquals(
        Http2ErrorCode.PROTOCOL_ERROR.code(), Http2TestFrames.readInt(frames.get(0).payload(), 4));
    assertEquals(0, run(java.util.Arrays.copyOf(Http2TestFrames.PREFACE, 12)).output().length);
  }

  @Test
  void unopenedLowerStreamIdentifierProducesConnectionProtocolError() throws Exception {
    byte[] request = {(byte) 0x82, (byte) 0x86, (byte) 0x84, (byte) 0x81};
    byte[] streamThree =
        Http2TestFrames.frame(
            FrameType.HEADERS,
            FrameFlags.END_HEADERS | FrameFlags.END_STREAM,
            3,
            request);
    byte[] lowerStream =
        Http2TestFrames.frame(
            FrameType.HEADERS,
            FrameFlags.END_HEADERS | FrameFlags.END_STREAM,
            1,
            request);

    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(
            run(
                    Http2TestFrames.concat(
                        Http2TestFrames.PREFACE,
                        Http2TestFrames.settings(),
                        streamThree,
                        lowerStream))
                .output());
    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(FrameType.GOAWAY.code(), goAway.type());
    assertEquals(
        Http2ErrorCode.PROTOCOL_ERROR.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }

  @Test
  void firstPeerFrameMustBeSettings() throws Exception {
    byte[] ping = Http2TestFrames.frame(FrameType.PING, 0, 0, new byte[8]);
    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(run(Http2TestFrames.concat(Http2TestFrames.PREFACE, ping)).output());

    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(FrameType.GOAWAY.code(), goAway.type());
    assertEquals(
        Http2ErrorCode.PROTOCOL_ERROR.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }

  @Test
  void settingsAcknowledgementCannotReplaceInitialPeerSettings() throws Exception {
    byte[] ack = Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[0]);
    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(run(Http2TestFrames.concat(Http2TestFrames.PREFACE, ack)).output());

    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(
        Http2ErrorCode.PROTOCOL_ERROR.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }

  @Test
  void invalidHpackBlockProducesCompressionError() throws Exception {
    byte[] headers =
        Http2TestFrames.frame(
            FrameType.HEADERS, FrameFlags.END_HEADERS, 1, new byte[] {(byte) 0x80});
    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(
            run(Http2TestFrames.concat(
                    Http2TestFrames.PREFACE, Http2TestFrames.settings(), headers))
                .output());

    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(
        Http2ErrorCode.COMPRESSION_ERROR.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }

  @Test
  void frameInterleavingDuringContinuationSequenceIsProtocolError() throws Exception {
    byte[] incompleteHeaders =
        Http2TestFrames.frame(FrameType.HEADERS, 0, 1, new byte[] {(byte) 0x82});
    byte[] ping = Http2TestFrames.frame(FrameType.PING, 0, 0, new byte[8]);
    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(
            run(Http2TestFrames.concat(
                    Http2TestFrames.PREFACE, Http2TestFrames.settings(), incompleteHeaders, ping))
                .output());

    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(
        Http2ErrorCode.PROTOCOL_ERROR.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }

  @Test
  void settingsAckWithPayloadIsFrameSizeError() throws Exception {
    byte[] badAck = Http2TestFrames.frame(FrameType.SETTINGS, FrameFlags.ACK, 0, new byte[6]);
    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(
            run(Http2TestFrames.concat(Http2TestFrames.PREFACE, badAck)).output());

    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(
        Http2ErrorCode.FRAME_SIZE_ERROR.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }

  @Test
  void missingSettingsAcknowledgementTimesOutWithDedicatedErrorCode() throws Exception {
    byte[] initial = Http2TestFrames.concat(Http2TestFrames.PREFACE, Http2TestFrames.settings());
    InputStream stallsAfterInput =
        new InputStream() {
          private final ByteArrayInputStream delegate = new ByteArrayInputStream(initial);

          @Override
          public int read() throws java.io.IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n < 0 ? -1 : one[0] & 0xff;
          }

          @Override
          public int read(byte[] target, int off, int len) throws java.io.IOException {
            if (delegate.available() > 0) return delegate.read(target, off, len);
            try {
              Thread.sleep(15);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
              throw new java.io.IOException(e);
            }
            throw new SocketTimeoutException("simulated idle peer");
          }
        };
    Http2Connection connection = new Http2Connection(delta -> {}, 5);
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Http2FrameWriter writer = new Http2FrameWriter(output::write, 5_000);
    try {
      connection.run(new BufferedByteSource(stallsAfterInput, null), writer, () -> false);
    } finally {
      writer.close();
    }

    List<Http2TestFrames.WireFrame> frames = Http2TestFrames.parse(output.toByteArray());
    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(
        Http2ErrorCode.SETTINGS_TIMEOUT.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }

  static RunResult run(byte[] input) throws Exception {
    Http2Connection connection = new Http2Connection();
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    Http2FrameWriter writer = new Http2FrameWriter(output::write, 5_000);
    try {
      connection.run(
          new BufferedByteSource(new ByteArrayInputStream(input), null), writer, () -> false);
      writer.drain();
    } finally {
      writer.close();
    }
    return new RunResult(connection, output.toByteArray());
  }

  record RunResult(Http2Connection connection, byte[] output) {}
}
