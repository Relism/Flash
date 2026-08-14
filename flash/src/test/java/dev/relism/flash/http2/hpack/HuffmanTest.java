package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.Http2Exception;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Random;
import org.junit.jupiter.api.Test;

class HuffmanTest {

  private static byte[] hex(String s) {
    return HexFormat.of().parseHex(s);
  }

  private static byte[] decode(byte[] encoded, int maxOut) {
    byte[] dst = new byte[maxOut];
    int n = Huffman.decode(encoded, 0, encoded.length, dst, 0, dst.length);
    byte[] result = new byte[n];
    System.arraycopy(dst, 0, result, 0, n);
    return result;
  }

  // --- RFC 7541 Appendix C.4 / C.6: official Huffman vectors ---

  @Test
  void appendixC41_wwwExampleCom() {
    byte[] encoded = hex("f1e3c2e5f23a6ba0ab90f4ff");
    assertEquals("www.example.com", new String(decode(encoded, 64), StandardCharsets.UTF_8));
  }

  @Test
  void appendixC42_noCache() {
    byte[] encoded = hex("a8eb10649cbf");
    assertEquals("no-cache", new String(decode(encoded, 64), StandardCharsets.UTF_8));
  }

  @Test
  void appendixC43_customKeyAndValue() {
    assertEquals(
        "custom-key", new String(decode(hex("25a849e95ba97d7f"), 64), StandardCharsets.UTF_8));
    assertEquals(
        "custom-value", new String(decode(hex("25a849e95bb8e8b4bf"), 64), StandardCharsets.UTF_8));
  }

  @Test
  void appendixC61_status302() {
    assertEquals("302", new String(decode(hex("6402"), 64), StandardCharsets.UTF_8));
  }

  @Test
  void appendixC61_private() {
    assertEquals("private", new String(decode(hex("aec3771a4b"), 64), StandardCharsets.UTF_8));
  }

  @Test
  void appendixC61_dateHeader() {
    byte[] encoded = hex("d07abe941054d444a8200595040b8166e082a62d1bff");
    assertEquals(
        "Mon, 21 Oct 2013 20:13:21 GMT", new String(decode(encoded, 64), StandardCharsets.UTF_8));
  }

  @Test
  void appendixC61_locationHeader() {
    byte[] encoded = hex("9d29ad171863c78f0b97c8e9ae82ae43d3");
    assertEquals(
        "https://www.example.com", new String(decode(encoded, 64), StandardCharsets.UTF_8));
  }

  @Test
  void appendixC62_status307() {
    assertEquals("307", new String(decode(hex("640eff"), 64), StandardCharsets.UTF_8));
  }

  // --- encode() matches the RFC's own bytes ---

  @Test
  void encode_matchesRfcVector_wwwExampleCom() {
    ByteWriter out = new ByteWriter(32);
    byte[] src = "www.example.com".getBytes(StandardCharsets.UTF_8);
    Huffman.encode(out, src, 0, src.length);
    assertArrayEquals(
        hex("f1e3c2e5f23a6ba0ab90f4ff"), java.util.Arrays.copyOf(out.array(), out.length()));
  }

  @Test
  void encode_matchesRfcVector_noCache() {
    ByteWriter out = new ByteWriter(32);
    byte[] src = "no-cache".getBytes(StandardCharsets.UTF_8);
    Huffman.encode(out, src, 0, src.length);
    assertArrayEquals(hex("a8eb10649cbf"), java.util.Arrays.copyOf(out.array(), out.length()));
  }

  @Test
  void encodedLength_matchesActualEncodedSize() {
    byte[] src = "www.example.com".getBytes(StandardCharsets.UTF_8);
    assertEquals(12, Huffman.encodedLength(src, 0, src.length));
  }

  // --- round trip: every byte value individually ---

  @Test
  void everyByteValue_roundTrips() {
    for (int v = 0; v <= 255; v++) {
      byte[] src = {(byte) v};
      ByteWriter out = new ByteWriter(8);
      Huffman.encode(out, src, 0, 1);
      byte[] decoded = decode(java.util.Arrays.copyOf(out.array(), out.length()), 4);
      assertArrayEquals(src, decoded, "byte value " + v);
    }
  }

  // --- round trip: random strings ---

  @Test
  void randomStrings_roundTrip() {
    Random rnd = new Random(42);
    for (int trial = 0; trial < 500; trial++) {
      int len = rnd.nextInt(200);
      byte[] src = new byte[len];
      rnd.nextBytes(src);
      ByteWriter out = new ByteWriter(64);
      Huffman.encode(out, src, 0, len);
      byte[] encoded = java.util.Arrays.copyOf(out.array(), out.length());
      byte[] decoded = decode(encoded, len + 8);
      assertArrayEquals(src, decoded, "trial " + trial + " len " + len);
    }
  }

  @Test
  void asciiHeaderLikeStrings_roundTrip() {
    String[] samples = {
      "",
      "a",
      "GET",
      "POST",
      "application/json",
      "text/html; charset=utf-8",
      "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36",
      "Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9",
      "0",
      "12345",
      "!!!???...",
    };
    for (String s : samples) {
      byte[] src = s.getBytes(StandardCharsets.UTF_8);
      ByteWriter out = new ByteWriter(64);
      Huffman.encode(out, src, 0, src.length);
      byte[] encoded = java.util.Arrays.copyOf(out.array(), out.length());
      byte[] decoded = decode(encoded, src.length + 8);
      assertArrayEquals(src, decoded, "sample: " + s);
    }
  }

  // --- invalid padding ---

  @Test
  void decode_paddingLongerThanSevenBits_throws() {
    // "no-cache" is 6 bytes Huffman-encoded (a8eb10649cbf); appending a full extra byte of
    // all-1s padding (8+ bits of padding total) must be rejected.
    byte[] encoded = hex("a8eb10649cbfff");
    assertThrows(Http2Exception.class, () -> decode(encoded, 64));
  }

  @Test
  void decode_paddingNotAllOnes_throws() {
    // '0' (symbol 48) is the 5-bit code 00000; the correct padding to fill the remaining 3
    // bits of the byte is 111 (0x07), decoding cleanly to "0". Replacing that padding with
    // 000 (0x00) leaves the walk 3 bits into the "000..." region of the trie (shared by
    // '0'/'1'/'2'/'a', none of which complete in exactly 3 bits) -- not the root, and not on
    // the all-1s padding spine, so it must be rejected.
    byte[] validPadding = {0x07};
    assertEquals("0", new String(decode(validPadding, 8), StandardCharsets.UTF_8));

    byte[] invalidPadding = {0x00};
    assertThrows(Http2Exception.class, () -> decode(invalidPadding, 8));
  }

  @Test
  void decode_incompleteCodeAtEnd_throws() {
    // Truncate "no-cache"'s encoding mid-code (not a valid prefix of the ones-spine).
    byte[] full = hex("a8eb10649cbf");
    byte[] truncated = java.util.Arrays.copyOf(full, full.length - 1);
    assertThrows(Http2Exception.class, () -> decode(truncated, 64));
  }

  // --- EOS symbol in input ---

  @Test
  void decode_eosSymbolInInput_throws() {
    // EOS is 30 ones: 0x3fffffff -- encode it directly as 4 bytes, left-aligned to a byte boundary.
    // 30 ones followed by 2 padding ones = 0xFF 0xFF 0xFF 0xFF.
    byte[] encoded = {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
    assertThrows(Http2Exception.class, () -> decode(encoded, 64));
  }

  // --- output bound enforced during decode ---

  @Test
  void decode_outputExceedingDstLimit_throws() {
    byte[] encoded = hex("f1e3c2e5f23a6ba0ab90f4ff"); // "www.example.com", 16 bytes decoded
    assertThrows(Http2Exception.class, () -> decode(encoded, 10));
  }

  @Test
  void decode_outputExactlyAtDstLimit_succeeds() {
    byte[] encoded = hex("f1e3c2e5f23a6ba0ab90f4ff");
    byte[] result = decode(encoded, 16);
    assertEquals("www.example.com", new String(result, StandardCharsets.UTF_8));
  }

  // --- empty string ---

  @Test
  void decode_emptyInput_producesEmptyOutput() {
    byte[] result = decode(new byte[0], 8);
    assertEquals(0, result.length);
  }

  @Test
  void encode_emptyInput_producesEmptyOutput() {
    ByteWriter out = new ByteWriter(8);
    Huffman.encode(out, new byte[0], 0, 0);
    assertEquals(0, out.length());
  }

  // --- structural invariant the nibble-FSM's "at most one symbol per nibble" design relies on ---

  @Test
  void everyRealCodeIsAtLeastFiveBitsLong() throws Exception {
    var lengthsField = Huffman.class.getDeclaredField("LENGTHS");
    lengthsField.setAccessible(true);
    int[] lengths = (int[]) lengthsField.get(null);
    for (int i = 0; i < 256; i++) {
      assertTrue(lengths[i] >= 5, "symbol " + i + " has length " + lengths[i] + " < 5");
    }
  }
}
