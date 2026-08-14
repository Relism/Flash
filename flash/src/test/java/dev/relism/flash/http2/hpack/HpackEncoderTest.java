package dev.relism.flash.http2.hpack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.fpr.core.ByteView;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class HpackEncoderTest {
  @Test
  void status200IsOneIndexedByte() {
    ByteWriter out = new ByteWriter(16);
    HpackEncoder.writeIndexed(out, 8);
    assertEquals(1, out.length());
    assertEquals(0x88, out.array()[0] & 0xff);
  }

  @Test
  void representationsRoundTripThroughDecoder() {
    ByteWriter out = new ByteWriter(128);
    HpackEncoder.writeDynamicTableSizeUpdateZero(out);
    HpackEncoder.writeIndexed(out, 8);
    HpackEncoder.writeLiteralWithNameIndex(out, 31, ascii("application/json"), true);
    HpackEncoder.writeLiteral(out, ascii("X-Trace"), ascii("abc123"));
    HpackEncoder.writeLiteralNeverIndexed(out, ascii("authorization"), ascii("secret"), false);

    List<String> fields = new ArrayList<>();
    List<Boolean> sensitive = new ArrayList<>();
    new HpackDecoder()
        .decode(
            out.array(),
            0,
            out.length(),
            (name, value, never) -> {
              fields.add(text(name) + "=" + text(value));
              sensitive.add(never);
            });

    assertEquals(
        List.of(
            ":status=200",
            "content-type=application/json",
            "x-trace=abc123",
            "authorization=secret"),
        fields);
    assertEquals(List.of(false, false, false, true), sensitive);
  }

  @Test
  void tableSizeUpdateZeroIsCanonical() {
    ByteWriter out = new ByteWriter(16);
    HpackEncoder.writeDynamicTableSizeUpdateZero(out);
    assertArrayEquals(new byte[] {0x20}, java.util.Arrays.copyOf(out.array(), out.length()));
  }

  @Test
  void huffmanLiteralIsSmallerForTypicalValue() {
    byte[] value = ascii("application/json");
    ByteWriter raw = new ByteWriter(32);
    ByteWriter compressed = new ByteWriter(32);
    HpackEncoder.writeLiteralWithNameIndex(raw, 31, value, false);
    HpackEncoder.writeLiteralWithNameIndex(compressed, 31, value, true);
    assertTrue(compressed.length() < raw.length());
  }

  private static byte[] ascii(String value) {
    return value.getBytes(StandardCharsets.US_ASCII);
  }

  private static String text(ByteView value) {
    byte[] bytes = new byte[value.length()];
    for (int i = 0; i < bytes.length; i++) bytes[i] = value.byteAt(i);
    return new String(bytes, StandardCharsets.US_ASCII);
  }
}
