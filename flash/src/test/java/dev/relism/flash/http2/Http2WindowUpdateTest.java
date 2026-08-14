package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http2.frame.FrameType;
import java.util.List;
import org.junit.jupiter.api.Test;

class Http2WindowUpdateTest {
  @Test
  void connectionWindowUpdateIncreasesSendWindow() throws Exception {
    Http2ConnectionHandshakeTest.RunResult result = runWindowUpdate(10_000);
    assertEquals(75_535, result.connection().connectionSendWindow());
  }

  @Test
  void zeroIncrementIsProtocolError() throws Exception {
    assertGoAwayCode(Http2ErrorCode.PROTOCOL_ERROR, runWindowUpdate(0).output());
  }

  @Test
  void connectionWindowOverflowIsFlowControlError() throws Exception {
    assertGoAwayCode(
        Http2ErrorCode.FLOW_CONTROL_ERROR, runWindowUpdate(Integer.MAX_VALUE).output());
  }

  private static Http2ConnectionHandshakeTest.RunResult runWindowUpdate(int increment)
      throws Exception {
    byte[] payload = {
      (byte) (increment >>> 24),
      (byte) (increment >>> 16),
      (byte) (increment >>> 8),
      (byte) increment
    };
    return Http2ConnectionHandshakeTest.run(
        Http2TestFrames.concat(
            Http2TestFrames.PREFACE,
            Http2TestFrames.settings(),
            Http2TestFrames.frame(FrameType.WINDOW_UPDATE, 0, 0, payload)));
  }

  private static void assertGoAwayCode(Http2ErrorCode expected, byte[] output) {
    List<Http2TestFrames.WireFrame> frames = Http2TestFrames.parse(output);
    Http2TestFrames.WireFrame goAway = frames.get(frames.size() - 1);
    assertEquals(FrameType.GOAWAY.code(), goAway.type());
    assertEquals(expected.code(), Http2TestFrames.readInt(goAway.payload(), 4));
  }
}
