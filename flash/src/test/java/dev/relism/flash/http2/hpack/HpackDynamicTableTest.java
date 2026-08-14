package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.Http2Exception;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HpackDynamicTableTest {
  private static PooledSlice view(String text) {
    byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
    PooledSlice result = new PooledSlice();
    result.reset(bytes, 0, bytes.length);
    return result;
  }

  private static String text(PooledSlice value) {
    return new String(value.array(), value.offset(), value.length(), StandardCharsets.US_ASCII);
  }

  @Test
  void newestEntryHasLowestDynamicIndex() {
    HpackDynamicTable table = new HpackDynamicTable(256);
    table.add(view("a"), view("one"));
    table.add(view("b"), view("two"));
    PooledSlice name = new PooledSlice();
    PooledSlice value = new PooledSlice();
    table.get(1, name, value);
    assertEquals("b", text(name));
    assertEquals("two", text(value));
    table.get(2, name, value);
    assertEquals("a", text(name));
  }

  @Test
  void evictsOldestEntriesByRfcSize() {
    HpackDynamicTable table = new HpackDynamicTable(70);
    table.add(view("a"), view("1")); // 34
    table.add(view("b"), view("2")); // 34
    table.add(view("c"), view("3")); // evicts a
    assertEquals(2, table.count());
    PooledSlice name = new PooledSlice();
    table.get(2, name, new PooledSlice());
    assertEquals("b", text(name));
  }

  @Test
  void oversizedEntryClearsTableWithoutInsertion() {
    HpackDynamicTable table = new HpackDynamicTable(40);
    table.add(view("a"), view("1"));
    table.add(view("long-name"), view("long-value"));
    assertEquals(0, table.count());
    assertEquals(0, table.size());
  }

  @Test
  void sizeUpdateCannotExceedAdvertisedMaximum() {
    HpackDynamicTable table = new HpackDynamicTable(128);
    assertThrows(Http2Exception.class, () -> table.setMaximumSize(129));
    table.setMaximumSize(0);
    assertEquals(0, table.count());
  }

  @Test
  void compactionPreservesLiveEntries() {
    HpackDynamicTable table = new HpackDynamicTable(96);
    for (int i = 0; i < 30; i++) table.add(view("name" + i), view("v" + i));
    PooledSlice name = new PooledSlice();
    PooledSlice value = new PooledSlice();
    table.get(1, name, value);
    assertEquals("name29", text(name));
    assertEquals("v29", text(value));
  }
}
