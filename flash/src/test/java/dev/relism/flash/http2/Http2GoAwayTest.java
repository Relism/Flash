package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import java.util.List;
import org.junit.jupiter.api.Test;

class Http2GoAwayTest {
  private static final byte[] SHUTDOWN_PING = {
    (byte) 0x46, (byte) 0x4c, (byte) 0x41, (byte) 0x53,
    (byte) 0x48, (byte) 0x47, (byte) 0x4f, (byte) 0x21
  };

  @Test
  void gracefulShutdownUsesTwoGoAwayStagesSeparatedByPingRoundTrip() throws Exception {
    byte[] input =
        Http2TestFrames.concat(
            Http2TestFrames.PREFACE,
            Http2TestFrames.settings(),
            Http2TestFrames.frame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1, new byte[0]),
            Http2TestFrames.frame(FrameType.PING, FrameFlags.ACK, 0, SHUTDOWN_PING));

    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(Http2ConnectionHandshakeTest.run(input).output());
    List<Http2TestFrames.WireFrame> goAways =
        frames.stream().filter(frame -> frame.type() == FrameType.GOAWAY.code()).toList();

    assertEquals(2, goAways.size());
    assertEquals(Integer.MAX_VALUE, Http2TestFrames.readInt(goAways.get(0).payload(), 0));
    assertEquals(1, Http2TestFrames.readInt(goAways.get(1).payload(), 0));
    assertEquals(
        Http2ErrorCode.NO_ERROR.code(), Http2TestFrames.readInt(goAways.get(0).payload(), 4));
    assertEquals(
        Http2ErrorCode.NO_ERROR.code(), Http2TestFrames.readInt(goAways.get(1).payload(), 4));

    int firstGoAway = indexOf(frames, FrameType.GOAWAY.code(), 0);
    int ping = indexOf(frames, FrameType.PING.code(), firstGoAway + 1);
    int secondGoAway = indexOf(frames, FrameType.GOAWAY.code(), firstGoAway + 1);
    assertTrue(firstGoAway < ping && ping < secondGoAway);
    assertArrayEquals(SHUTDOWN_PING, frames.get(ping).payload());
  }

  @Test
  void receivedGoAwayRecordsPeerState() throws Exception {
    byte[] payload = new byte[8];
    payload[3] = 7;
    payload[7] = (byte) Http2ErrorCode.ENHANCE_YOUR_CALM.code();
    Http2ConnectionHandshakeTest.RunResult result =
        Http2ConnectionHandshakeTest.run(
            Http2TestFrames.concat(
                Http2TestFrames.PREFACE,
                Http2TestFrames.settings(),
                Http2TestFrames.frame(FrameType.GOAWAY, 0, 0, payload)));

    assertEquals(7, result.connection().peerLastStreamId());
    assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM.code(), result.connection().peerErrorCode());
  }

  private static int indexOf(List<Http2TestFrames.WireFrame> frames, int type, int from) {
    for (int i = from; i < frames.size(); i++) {
      if (frames.get(i).type() == type) return i;
    }
    return -1;
  }
}
