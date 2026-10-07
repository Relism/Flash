package dev.relism.flash.http2.stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class Http2StreamLeakTest {
  @Test
  void oneHundredThousandAcquireReleaseCyclesReuseOneStream() {
    Http2StreamTable table = new Http2StreamTable(100);
    for (int i = 0; i < 100_000; i++) {
      Http2Stream stream = table.acquire((i << 1) | 1);
      table.remove(stream.id());
      table.release(stream);
    }
    assertEquals(1, table.createdCount());
    assertEquals(1, table.freeCount());
    assertEquals(0, table.size());
  }

  @Test
  void aStreamIsReleasedOnlyOnce() {
    Http2StreamTable table = new Http2StreamTable(1);
    Http2Stream stream = table.acquire(1);
    table.remove(1);

    table.release(stream);
    table.release(stream);

    assertEquals(1, table.freeCount());
  }
}
