package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.Http2Exception;
import org.junit.jupiter.api.Test;

class HpackDecoderSecurityTest {
  private static final HeaderSink DISCARD = (name, value, never) -> {};

  @Test
  void rejectsZeroAndOutOfRangeIndices() {
    HpackDecoder decoder = new HpackDecoder();
    assertThrows(
        Http2Exception.class, () -> decoder.decode(new byte[] {(byte) 0x80}, 0, 1, DISCARD));
    assertThrows(
        Http2Exception.class, () -> decoder.decode(new byte[] {(byte) 0xff, 0}, 0, 2, DISCARD));
  }

  @Test
  void rejectsLateAndOversizedTableUpdates() {
    HpackDecoder decoder = new HpackDecoder(128, 1024);
    assertThrows(
        Http2Exception.class, () -> decoder.decode(new byte[] {(byte) 0x82, 0x20}, 0, 2, DISCARD));

    ByteWriter update = new ByteWriter(8);
    HpackIntegers.encode(update, 0x20, 5, 129);
    assertThrows(
        Http2Exception.class, () -> decoder.decode(update.array(), 0, update.length(), DISCARD));
  }

  @Test
  void headerListLimitIsReportedOnlyAfterDynamicStateIsUpdated() {
    HpackDecoder decoder = new HpackDecoder(256, 40);
    byte[] block = java.util.HexFormat.of().parseHex("40016101624001630164");
    HeaderListSizeException error =
        assertThrows(
            HeaderListSizeException.class, () -> decoder.decode(block, 0, block.length, DISCARD));
    assertTrue(error.decodedSize() > 40);
    assertEquals(2, decoder.dynamicTable().count());
  }

  @Test
  void malformedStringsAndIntegerBombsAreCompressionErrors() {
    HpackDecoder decoder = new HpackDecoder();
    assertThrows(
        Http2Exception.class, () -> decoder.decode(new byte[] {0x40, 0x01}, 0, 2, DISCARD));
    byte[] bomb = {0x3f, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x80};
    assertThrows(Http2Exception.class, () -> decoder.decode(bomb, 0, bomb.length, DISCARD));
  }

  @Test
  void indexedDynamicNameSurvivesEvictionDuringInsertion() {
    HpackDecoder decoder = new HpackDecoder(48, 1024);
    byte[] first = java.util.HexFormat.of().parseHex("4001610d31323334353637383930313233");
    decoder.decode(first, 0, first.length, DISCARD);

    // Dynamic index 62 supplies the name "a". Adding the new value evicts the referenced entry.
    byte[] second = java.util.HexFormat.of().parseHex("7e0d6162636465666768696a6b6c6d");
    decoder.decode(second, 0, second.length, DISCARD);

    dev.relism.flash.bytes.PooledSlice name = new dev.relism.flash.bytes.PooledSlice();
    dev.relism.flash.bytes.PooledSlice value = new dev.relism.flash.bytes.PooledSlice();
    decoder.dynamicTable().get(1, name, value);
    assertEquals('a', name.byteAt(0));
  }
}
