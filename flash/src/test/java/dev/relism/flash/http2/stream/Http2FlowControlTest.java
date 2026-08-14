package dev.relism.flash.http2.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Http2FlowControlTest {
  @Test
  void receiveWindowsReopenAtHalfWindowAtBothLevels() throws Exception {
    AtomicInteger connectionUpdates = new AtomicInteger();
    AtomicInteger streamUpdates = new AtomicInteger();
    Http2FlowController controller =
        new Http2FlowController(
            (streamId, increment) -> {
              if (streamId == 0) connectionUpdates.addAndGet(increment);
              else streamUpdates.addAndGet(increment);
            });
    Http2Stream stream = new Http2StreamTable(1).acquire(1);
    int half = Http2Limits.INITIAL_WINDOW_SIZE_LOCAL / 2;

    controller.receiveConnectionBytes(half);
    controller.receiveStreamBytes(stream, half);
    controller.consumed(stream, half);

    assertEquals(half, connectionUpdates.get());
    assertEquals(half, streamUpdates.get());
    assertEquals(Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL, controller.connectionReceiveWindow());
  }

  @Test
  void connectionAndStreamUnderflowUseTheirCorrectErrorScope() {
    Http2FlowController controller = new Http2FlowController((streamId, increment) -> {});
    Http2Stream stream = new Http2StreamTable(1).acquire(1);

    assertSame(
        Http2Exception.FLOW_CONTROL_ERROR,
        assertThrows(
            Http2Exception.class,
            () ->
                controller.receiveConnectionBytes(
                    Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL + 1)));
    assertEquals(
        dev.relism.flash.http2.Http2ErrorCode.FLOW_CONTROL_ERROR,
        assertThrows(
                dev.relism.flash.http2.Http2StreamException.class,
                () ->
                    controller.receiveStreamBytes(
                        stream, Http2Limits.INITIAL_WINDOW_SIZE_LOCAL + 1))
            .errorCode());
  }

  @Test
  void sendReservationHonoursBothWindowsAndRejectsOverflow() {
    Http2FlowController controller = new Http2FlowController((streamId, increment) -> {});
    Http2Stream stream = new Http2StreamTable(1).acquire(1);

    assertEquals(65_535, controller.reserveSend(stream, 100_000));
    assertEquals(0, controller.reserveSend(stream, 1));
    controller.increaseConnectionSendWindow(Integer.MAX_VALUE);
    assertSame(
        Http2Exception.FLOW_CONTROL_ERROR,
        assertThrows(Http2Exception.class, () -> controller.increaseConnectionSendWindow(1)));
  }

  @Test
  void emptyDataFrameCounterCrossesTheConfiguredLimitDeterministically() {
    Http2Stream stream = new Http2StreamTable(1).acquire(1);
    for (int i = 1; i <= Http2Limits.MAX_EMPTY_DATA_FRAMES_PER_STREAM; i++) {
      assertEquals(i, stream.incrementEmptyDataFrames());
    }
    assertEquals(
        Http2Limits.MAX_EMPTY_DATA_FRAMES_PER_STREAM + 1, stream.incrementEmptyDataFrames());
    stream.resetEmptyDataFrames();
    assertEquals(1, stream.incrementEmptyDataFrames());
  }
}
