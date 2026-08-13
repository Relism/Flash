package dev.relism.flash.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebSocketLoopTest {
  @Test
  void onOpenFailureStillReportsErrorClosesAndReleasesSession() {
    RuntimeException failure = new RuntimeException("open failed");
    AtomicReference<Throwable> reported = new AtomicReference<>();
    AtomicInteger closes = new AtomicInteger();
    WebSocketSession session =
        new WebSocketSession(
            new ByteArrayInputStream(new byte[0]), new ByteArrayOutputStream(), 128);

    WebSocketLoop.run(
        session,
        new WebSocketHandler() {
          @Override
          public void onOpen(WebSocketSession opened) {
            throw failure;
          }

          @Override
          public void onMessage(WebSocketSession opened, WebSocketFrame frame) {}

          @Override
          public void onError(WebSocketSession opened, Throwable error) {
            reported.set(error);
          }

          @Override
          public void onClose(WebSocketSession opened, int code) {
            closes.incrementAndGet();
          }
        });

    assertSame(failure, reported.get());
    assertEquals(1, closes.get());
    assertFalse(session.isOpen());
  }

  @Test
  void onCloseFailureCannotPreventTransportRelease() {
    AtomicInteger inputCloses = new AtomicInteger();
    WebSocketSession session =
        new WebSocketSession(
            new InputStream() {
              @Override
              public int read() {
                return -1;
              }

              @Override
              public void close() {
                inputCloses.incrementAndGet();
              }
            },
            new ByteArrayOutputStream(),
            128);

    assertThrows(
        RuntimeException.class,
        () ->
            WebSocketLoop.run(
                session,
                new WebSocketHandler() {
                  @Override
                  public void onOpen(WebSocketSession opened) {}

                  @Override
                  public void onMessage(WebSocketSession opened, WebSocketFrame frame) {}

                  @Override
                  public void onClose(WebSocketSession opened, int code) {
                    throw new RuntimeException("close failed");
                  }
                }));

    assertEquals(1, inputCloses.get());
    assertFalse(session.isOpen());
  }
}
