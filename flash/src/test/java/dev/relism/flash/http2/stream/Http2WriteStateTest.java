package dev.relism.flash.http2.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class Http2WriteStateTest {

  @Test
  void aBatchCompletedBeforeTheWriterWaitsIsNotLost() throws Exception {
    Http2Stream stream = new Http2StreamTable(1).acquire(1);
    stream.enterDuplex();
    assertTrue(stream.beginResponseBatch());
    assertTrue(stream.completeDuplexBatch());

    stream.awaitResponseBatch();

    assertFalse(stream.responseInFlight());
    assertTrue(stream.leaveDuplex());
  }

  @Test
  void aWindowUpdateBeforeTheWriterWaitsIsObserved() throws Exception {
    Http2Stream stream = new Http2StreamTable(1).acquire(1);
    Http2FlowController flow = new Http2FlowController((id, increment) -> {});
    assertEquals(65_535, flow.reserveSend(stream, 65_535));
    flow.increaseConnectionSendWindow(1);
    flow.increaseStreamSendWindow(stream, 1);

    assertEquals(1, stream.awaitSendWindow(flow, 1));
  }

  @Test
  void resetWakesAWriterWaitingForFlowControl() throws Exception {
    Http2Stream stream = new Http2StreamTable(1).acquire(1);
    Http2FlowController flow = new Http2FlowController((id, increment) -> {});
    flow.reserveSend(stream, 65_535);
    stream.enterDuplex();
    AtomicReference<Thread> writer = new AtomicReference<>();

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Integer> waiting = executor.submit(() -> {
        writer.set(Thread.currentThread());
        return stream.awaitSendWindow(flow, 1);
      });
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
      while ((writer.get() == null || writer.get().getState() != Thread.State.WAITING)
          && System.nanoTime() < deadline) Thread.onSpinWait();
      assertEquals(Thread.State.WAITING, writer.get().getState());

      stream.cancel();

      ExecutionException failure = assertThrows(ExecutionException.class,
          () -> waiting.get(1, TimeUnit.SECONDS));
      assertTrue(failure.getCause() instanceof IOException);
    }
  }
}
