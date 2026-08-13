package dev.relism.flash.http2.stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

class Http2StreamTableTest {
  @Test
  void insertLookupRemoveAtCapacityAndAcrossProbeClusters() {
    Http2StreamTable table = new Http2StreamTable(8);
    Http2Stream[] streams = new Http2Stream[8];
    for (int i = 0; i < streams.length; i++) {
      streams[i] = table.acquire(i * 2 + 1);
      assertSame(streams[i], table.get(i * 2 + 1));
    }
    assertNull(table.acquire(99));
    for (int i = 0; i < streams.length; i += 2) {
      assertSame(streams[i], table.remove(streams[i].id()));
      table.release(streams[i]);
    }
    for (int i = 1; i < streams.length; i += 2) {
      assertSame(streams[i], table.get(streams[i].id()));
    }
  }
}
