package dev.relism.flash.http2.stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
      table.release(streams[i], streams[i].id());
    }
    for (int i = 1; i < streams.length; i += 2) {
      assertSame(streams[i], table.get(streams[i].id()));
    }
  }

  @Test
  void staleRetirementCannotRemoveAReusedPooledStream() {
    Http2StreamTable table = new Http2StreamTable(1);
    Http2Stream firstGeneration = table.acquire(1);

    assertTrue(table.retire(firstGeneration, 1));
    Http2Stream secondGeneration = table.acquire(3);
    assertSame(firstGeneration, secondGeneration);

    assertFalse(table.retire(firstGeneration, 1));
    assertSame(secondGeneration, table.get(3));
  }

  @Test
  void aLateReleaseCannotRecycleAReusedPooledStream() {
    Http2StreamTable table = new Http2StreamTable(1);
    Http2Stream stream = table.acquire(1);
    table.remove(1);
    table.release(stream, 1);
    assertSame(stream, table.acquire(3));

    // a thread that still held generation 1 lets go of it
    table.release(stream, 1);

    assertEquals(3, stream.id());
    assertEquals(0, table.freeCount());
  }

  @Test
  void boundedTombstonesDistinguishNormalClosureFromReset() {
    Http2StreamTable table = new Http2StreamTable(2);
    Http2Stream stream = table.acquire(1);

    assertTrue(table.retire(stream, 1));
    table.rememberReset(3);

    assertEquals(Http2StreamTable.CLOSED_NORMALLY, table.closedKind(1));
    assertEquals(Http2StreamTable.CLOSED_BY_RESET, table.closedKind(3));
    assertEquals(Http2StreamTable.CLOSED_UNKNOWN, table.closedKind(5));
  }

  @Test
  void detachedFinalWriteDoesNotConsumeLiveStreamCapacity() {
    Http2StreamTable table = new Http2StreamTable(1);
    Http2Stream first = table.acquire(1);

    assertTrue(table.detach(first, 1));
    Http2Stream second = table.acquire(3);
    assertNotNull(second);
    assertNotSame(first, second);

    table.release(first, 1);
    assertTrue(table.retire(second, 3));
    assertEquals(2, table.createdCount());
    assertEquals(2, table.freeCount());
  }
}
