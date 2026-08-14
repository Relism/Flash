package dev.relism.flash.http2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http2.message.DataBufferPool;
import dev.relism.flash.http2.message.Http2RequestBody;
import dev.relism.flash.http2.stream.Http2FlowController;
import dev.relism.flash.http2.stream.Http2Stream;
import dev.relism.flash.http2.stream.Http2StreamTable;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Http2BackpressureTest {
  @Test
  void windowUpdatesAreWithheldUntilTheHandlerConsumesQueuedData() throws Exception {
    AtomicInteger updates = new AtomicInteger();
    Http2FlowController flow =
        new Http2FlowController((streamId, increment) -> updates.addAndGet(increment));
    Http2Stream stream = new Http2StreamTable(1).acquire(1);
    DataBufferPool pool = new DataBufferPool(Http2Limits.MAX_FRAME_SIZE_LOCAL, 32);
    Http2RequestBody body = new Http2RequestBody(pool);
    body.begin(-1, false, bytes -> flow.consumed(stream, bytes));
    byte[] frame = new byte[Http2Limits.MAX_FRAME_SIZE_LOCAL];

    for (int i = 0; i < 32; i++) {
      flow.receiveConnectionBytes(frame.length);
      flow.receiveStreamBytes(stream, frame.length);
      body.offer(1, frame, 0, frame.length, frame.length);
    }
    assertEquals(0, updates.get(), "receiving alone must not reopen either window");

    body.finish(1);
    assertEquals(32L * frame.length, body.readAllBytes().length);
    assertEquals(2 * 32 * frame.length, updates.get());
    assertEquals(32, pool.availableCount());
  }
}
