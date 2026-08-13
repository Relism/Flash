package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.http2.Http2Exception;
import org.junit.jupiter.api.Test;

class HpackIntegersTest {

  // --- RFC 7541 Appendix C.1: official vectors ---

  @Test
  void appendixC11_10With5BitPrefix() {
    byte[] buf = {0x0a};
    long packed = HpackIntegers.decode(buf, 0, buf.length, 5);
    assertEquals(10, Pairs.hi(packed));
    assertEquals(1, Pairs.lo(packed));
  }

  @Test
  void appendixC12_1337With5BitPrefix() {
    byte[] buf = {(byte) 0x1f, (byte) 0x9a, 0x0a};
    long packed = HpackIntegers.decode(buf, 0, buf.length, 5);
    assertEquals(1337, Pairs.hi(packed));
    assertEquals(3, Pairs.lo(packed));
  }

  @Test
  void appendixC13_42With8BitPrefix() {
    byte[] buf = {0x2a};
    long packed = HpackIntegers.decode(buf, 0, buf.length, 8);
    assertEquals(42, Pairs.hi(packed));
    assertEquals(1, Pairs.lo(packed));
  }

  // --- position handling ---

  @Test
  void decode_startsAtNonZeroPosition_leavesPrecedingBytesUntouched() {
    byte[] buf = {(byte) 0xFF, 0x0a}; // garbage, then "10" with 5-bit prefix
    long packed = HpackIntegers.decode(buf, 1, buf.length, 5);
    assertEquals(10, Pairs.hi(packed));
    assertEquals(2, Pairs.lo(packed));
  }

  @Test
  void decode_ignoresHighBitsAboveThePrefix() {
    // High 3 bits simulate a representation's leading flag bits (e.g. 0xA0 = 101xxxxx);
    // only the low 5 bits are the integer's prefix.
    byte[] buf = {(byte) 0b101_01010}; // flags=101, prefix value=01010=10
    long packed = HpackIntegers.decode(buf, 0, buf.length, 5);
    assertEquals(10, Pairs.hi(packed));
  }

  // --- round-trip via encode() ---

  @Test
  void encode_thenDecode_roundTrips_acrossBoundaryValues() {
    int[] values = {0, 1, 30, 31, 32, 1337, 268_435_455};
    for (int prefixBits : new int[] {4, 5, 7, 8}) {
      for (int value : values) {
        ByteWriter out = new ByteWriter(16);
        HpackIntegers.encode(out, 0, prefixBits, value);
        long packed = HpackIntegers.decode(out.array(), 0, out.length(), prefixBits);
        assertEquals(value, Pairs.hi(packed), "prefixBits=" + prefixBits + " value=" + value);
        assertEquals(out.length(), Pairs.lo(packed));
      }
    }
  }

  @Test
  void encode_matchesRfcVector_1337With5BitPrefix() {
    ByteWriter out = new ByteWriter(16);
    HpackIntegers.encode(out, 0, 5, 1337);
    assertArrayEquals(
        new byte[] {(byte) 0x1f, (byte) 0x9a, 0x0a},
        java.util.Arrays.copyOf(out.array(), out.length()));
  }

  @Test
  void encode_preservesPrefixByteFlags() {
    ByteWriter out = new ByteWriter(16);
    HpackIntegers.encode(out, 0x80, 7, 5); // Indexed Header Field, index 5
    assertEquals((byte) 0x85, out.array()[0]);
  }

  // --- overflow / hostile-input safety (HPACK bomb) ---

  @Test
  void decode_exceedingMaxContinuationOctets_throwsCompressionError() {
    // 5-bit prefix all-ones (31), then 5 continuation octets all with the continue bit set
    // (0xFF) -- one more than MAX_CONTINUATION_OCTETS(4) tolerates.
    byte[] buf = {0x1f, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00};
    Http2Exception ex =
        assertThrows(Http2Exception.class, () -> HpackIntegers.decode(buf, 0, buf.length, 5));
    assertSame(dev.relism.flash.http2.Http2ErrorCode.COMPRESSION_ERROR, ex.errorCode());
  }

  @Test
  void decode_exactlyMaxContinuationOctets_succeeds() {
    // 4 continuation octets is the tolerated boundary -- must not throw.
    byte[] buf = {0x1f, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 0x00};
    long packed = HpackIntegers.decode(buf, 0, buf.length, 5);
    assertTrue(Pairs.hi(packed) > 0);
  }

  @Test
  void decode_truncatedAtPrefixByte_throws() {
    byte[] buf = {};
    assertThrows(Http2Exception.class, () -> HpackIntegers.decode(buf, 0, buf.length, 5));
  }

  @Test
  void decode_truncatedMidContinuation_throws() {
    // prefix says "keep reading" but the buffer ends immediately after.
    byte[] buf = {0x1f};
    assertThrows(Http2Exception.class, () -> HpackIntegers.decode(buf, 0, buf.length, 5));
  }

  @Test
  void decode_truncatedByLimit_notByBufferLength_throws() {
    // The buffer itself has more bytes, but `limit` (the current block's end) cuts it off --
    // decode must respect limit, not buf.length, since HPACK scratch buffers are reused and
    // may contain trailing bytes from a previous, larger block.
    byte[] buf = {0x1f, (byte) 0x9a, 0x0a, 0x00, 0x00};
    assertThrows(Http2Exception.class, () -> HpackIntegers.decode(buf, 0, 2, 5));
  }
}
