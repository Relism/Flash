package dev.relism.flash.http2.message;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http.RequestBody;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class Http2RequestBodyTest {
  @Test
  void inlineBodyFeedsTheProtocolNeutralRequestBodyWithOneMaterialization() {
    AtomicInteger consumed = new AtomicInteger();
    Http2RequestBody source = new Http2RequestBody(new DataBufferPool(16, 1));
    source.begin(3, true, consumed::addAndGet, 1024);
    source.offer(1, "abc".getBytes(StandardCharsets.US_ASCII), 0, 3, 5);
    source.finish(1);
    RequestBody body = new RequestBody();
    body.reset(source, 3, null, 0, 0);

    assertArrayEquals("abc".getBytes(StandardCharsets.US_ASCII), body.bytes());
    assertEquals(5, consumed.get());
    assertEquals(3, body.contentLength());
  }

  @Test
  void streamingBodyReusesAndReturnsPooledBuffers() throws Exception {
    DataBufferPool pool = new DataBufferPool(8, 2);
    AtomicInteger consumed = new AtomicInteger();
    Http2RequestBody source = new Http2RequestBody(pool);
    source.begin(-1, false, consumed::addAndGet, 1024);
    source.offer(1, new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, 8, 8);
    source.offer(1, new byte[] {9, 10}, 0, 2, 2);
    source.finish(1);

    assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10}, source.readAllBytes());
    assertEquals(10, consumed.get());
    assertEquals(2, pool.createdCount());
    assertEquals(2, pool.availableCount());
  }

  @Test
  void contentLengthMismatchIsAProtocolStreamError() {
    Http2RequestBody source = new Http2RequestBody(new DataBufferPool(8, 1));
    source.begin(4, true, bytes -> {}, 1024);
    source.offer(3, new byte[] {1, 2, 3}, 0, 3, 3);

    Http2StreamException failure =
        assertThrows(Http2StreamException.class, () -> source.finish(3));
    assertEquals(Http2ErrorCode.PROTOCOL_ERROR, failure.errorCode());
  }

  @Test
  void boundedPoolNeverAllocatesPastItsCapacity() {
    DataBufferPool pool = new DataBufferPool(4, 1);
    Http2RequestBody source = new Http2RequestBody(pool);
    source.begin(-1, false, bytes -> {}, 1024);
    source.offer(1, new byte[] {1, 2, 3, 4}, 0, 4, 4);

    Http2StreamException failure =
        assertThrows(
            Http2StreamException.class,
            () -> source.offer(1, new byte[] {2}, 0, 1, 1));
    assertEquals(Http2ErrorCode.ENHANCE_YOUR_CALM, failure.errorCode());
    assertEquals(1, pool.createdCount());
  }

  @Test
  void unknownLengthBodyCannotExceedTheConfiguredMaximum() {
    Http2RequestBody source = new Http2RequestBody(new DataBufferPool(8, 1));
    source.begin(-1, false, bytes -> {}, 1024);

    Http2StreamException failure =
        assertThrows(
            Http2StreamException.class,
            () ->
                source.offer(
                    1, new byte[1], 0, 1025, 1));
    assertEquals(Http2ErrorCode.PROTOCOL_ERROR, failure.errorCode());
  }

  @Test
  void resetBeforeEndStreamFailsTheReaderInsteadOfEndingTheBody() {
    Http2RequestBody streaming = new Http2RequestBody(new DataBufferPool(8, 1));
    streaming.begin(10, false, bytes -> {}, 1024);
    streaming.offer(1, new byte[] {1, 2, 3}, 0, 3, 3);
    streaming.cancel();
    assertThrows(IOException.class, streaming::readAllBytes);

    Http2RequestBody inline = new Http2RequestBody(new DataBufferPool(8, 1));
    inline.begin(10, true, bytes -> {}, 1024);
    inline.offer(1, new byte[] {1, 2, 3}, 0, 3, 3);
    inline.cancel();
    assertThrows(IOException.class, inline::readAllBytes);
  }

  @Test
  void cancelAfterEndStreamKeepsTheBodyWhole() throws Exception {
    Http2RequestBody inline = new Http2RequestBody(new DataBufferPool(8, 1));
    inline.begin(3, true, bytes -> {}, 1024);
    inline.offer(1, new byte[] {1, 2, 3}, 0, 3, 3);
    inline.finish(1);
    inline.cancel();
    assertArrayEquals(new byte[] {1, 2, 3}, inline.readAllBytes());
  }
}
