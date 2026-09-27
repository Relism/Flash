package dev.relism.flash.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.relism.flash.http.ContentType;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ResponseStreamTest {
  @Test
  void producerWritesBodyAndTrailersThroughBoundedBridge() throws Exception {
    Response response = new Response(200, ContentType.BINARY);
    response.streaming(stream -> {
      try {
        stream.write(new byte[] {1, 2, 3}, 0, 3);
        stream.trailer("grpc-status", "0");
      } catch (IOException failure) {
        throw new RuntimeException(failure);
      }
    });

    assertArrayEquals(new byte[] {1, 2, 3}, response.getStream().readAllBytes());
    assertEquals(true, response.hasTrailers());
  }

  @Test
  void writeAfterCloseFailsWithoutWritingMoreBytes() throws Exception {
    AtomicReference<IOException> failure = new AtomicReference<>();
    CountDownLatch attempted = new CountDownLatch(1);
    Response response = new Response(200, ContentType.BINARY);
    response.streaming(stream -> {
      try {
        stream.close();
        stream.write(new byte[] {1}, 0, 1);
      } catch (IOException expected) {
        failure.set(expected);
      } finally {
        attempted.countDown();
      }
    });

    assertArrayEquals(new byte[0], response.getStream().readAllBytes());
    assertEquals(true, attempted.await(1, TimeUnit.SECONDS));
    assertEquals("response stream is closed", failure.get().getMessage());
  }
}
