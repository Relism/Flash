package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.http2.Http2ConnectionScratch.ControlIntent;
import dev.relism.flash.http2.Http2ConnectionScratch.ControlKind;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class Http2PingTest {
  @Test
  void pingResponseEchoesOpaqueBytesExactly() throws Exception {
    byte[] opaque = "12345678".getBytes(StandardCharsets.US_ASCII);
    byte[] input =
        Http2TestFrames.concat(
            Http2TestFrames.PREFACE,
            Http2TestFrames.settings(),
            Http2TestFrames.frame(FrameType.PING, 0, 0, opaque));

    List<Http2TestFrames.WireFrame> frames =
        Http2TestFrames.parse(Http2ConnectionHandshakeTest.run(input).output());
    Http2TestFrames.WireFrame pong = frames.get(frames.size() - 1);

    assertEquals(FrameType.PING.code(), pong.type());
    assertEquals(FrameFlags.ACK, pong.flags());
    assertArrayEquals(opaque, pong.payload());
  }

  @Test
  void pingQueueIsStrictlyBounded() {
    Http2ConnectionScratch scratch = new Http2ConnectionScratch();
    List<ControlIntent> claimed = new ArrayList<>();
    for (int i = 0; i < Http2Limits.MAX_PING_QUEUE_DEPTH; i++) {
      claimed.add(scratch.acquire(ControlKind.PING));
    }

    Http2Exception error =
        assertThrows(Http2Exception.class, () -> scratch.acquire(ControlKind.PING));
    assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, error.errorCode());
    claimed.forEach(ControlIntent::completed);
  }
}
