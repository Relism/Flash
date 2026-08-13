package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.PooledSlice;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class HpackStaticTableTest {
  private static PooledSlice view(String value) {
    byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
    PooledSlice view = new PooledSlice();
    view.reset(bytes, 0, bytes.length);
    return view;
  }

  @Test
  void containsAllRfcEntriesAndUsesOneBasedIndices() {
    assertEquals(61, HpackStaticTable.LENGTH);
    assertArrayEquals(":authority".getBytes(StandardCharsets.US_ASCII), HpackStaticTable.name(1));
    assertArrayEquals(
        "gzip, deflate".getBytes(StandardCharsets.US_ASCII), HpackStaticTable.value(16));
    assertArrayEquals(
        "www-authenticate".getBytes(StandardCharsets.US_ASCII), HpackStaticTable.name(61));
    assertThrows(IndexOutOfBoundsException.class, () -> HpackStaticTable.name(0));
    assertThrows(IndexOutOfBoundsException.class, () -> HpackStaticTable.value(62));
  }

  @Test
  void findNameReturnsLowestIndexForRepeatedNames() {
    assertEquals(2, HpackStaticTable.findName(view(":method")));
    assertEquals(8, HpackStaticTable.findName(view(":status")));
    assertEquals(0, HpackStaticTable.findName(view("missing")));
  }

  @Test
  void findPairMatchesExactBytes() {
    assertEquals(2, HpackStaticTable.findPair(view(":method"), view("GET")));
    assertEquals(14, HpackStaticTable.findPair(view(":status"), view("500")));
    assertEquals(0, HpackStaticTable.findPair(view(":method"), view("get")));
  }
}
