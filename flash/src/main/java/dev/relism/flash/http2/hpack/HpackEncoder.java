package dev.relism.flash.http2.hpack;

import dev.relism.flash.bytes.ByteWriter;

/**
 * Stateless HPACK encoder for response header blocks. It uses the RFC 7541 static table and literal
 * fields without indexing; consequently, concurrent streams never share mutable encoder state.
 */
public final class HpackEncoder {
  private HpackEncoder() {}

  /** Declares that this endpoint will not use an encoder-side dynamic table. */
  public static void writeDynamicTableSizeUpdateZero(ByteWriter out) {
    HpackIntegers.encode(out, 0x20, 5, 0);
  }

  public static void writeIndexed(ByteWriter out, int staticIndex) {
    if (staticIndex < 1 || staticIndex > HpackStaticTable.LENGTH) {
      throw new IllegalArgumentException("invalid HPACK static index: " + staticIndex);
    }
    HpackIntegers.encode(out, 0x80, 7, staticIndex);
  }

  public static void writeLiteral(ByteWriter out, byte[] name, byte[] value) {
    writeLiteral(out, name, 0, name.length, value, 0, value.length, false);
  }

  public static void writeLiteral(
      ByteWriter out,
      byte[] name,
      int nameOff,
      int nameLen,
      byte[] value,
      int valueOff,
      int valueLen,
      boolean huffmanValue) {
    HpackIntegers.encode(out, 0, 4, 0);
    writeLowercaseName(out, name, nameOff, nameLen);
    writeString(out, value, valueOff, valueLen, huffmanValue);
  }

  public static void writeLiteralWithNameIndex(
      ByteWriter out, int nameIndex, byte[] value, boolean huffmanValue) {
    writeLiteralWithNameIndex(out, nameIndex, value, 0, value.length, huffmanValue);
  }

  public static void writeLiteralWithNameIndex(
      ByteWriter out,
      int nameIndex,
      byte[] value,
      int valueOff,
      int valueLen,
      boolean huffmanValue) {
    if (nameIndex < 1 || nameIndex > HpackStaticTable.LENGTH) {
      throw new IllegalArgumentException("invalid HPACK static name index: " + nameIndex);
    }
    HpackIntegers.encode(out, 0, 4, nameIndex);
    writeString(out, value, valueOff, valueLen, huffmanValue);
  }

  public static void writeLiteralNeverIndexed(
      ByteWriter out, byte[] name, byte[] value, boolean huffmanValue) {
    HpackIntegers.encode(out, 0x10, 4, 0);
    writeLowercaseName(out, name, 0, name.length);
    writeString(out, value, 0, value.length, huffmanValue);
  }

  public static void writeLiteralNeverIndexedWithNameIndex(
      ByteWriter out, int nameIndex, byte[] value, boolean huffmanValue) {
    HpackIntegers.encode(out, 0x10, 4, nameIndex);
    writeString(out, value, 0, value.length, huffmanValue);
  }

  private static void writeLowercaseName(ByteWriter out, byte[] name, int nameOff, int nameLen) {
    HpackIntegers.encode(out, 0, 7, nameLen);
    int end = nameOff + nameLen;
    for (int i = nameOff; i < end; i++) {
      int value = name[i] & 0xff;
      if (value >= 'A' && value <= 'Z') value += 'a' - 'A';
      assert value < 'A' || value > 'Z' : "HTTP/2 field names must be lowercase";
      out.writeByte((byte) value);
    }
  }

  private static void writeString(ByteWriter out, byte[] value, int off, int len, boolean huffman) {
    if (huffman) {
      HpackIntegers.encode(out, 0x80, 7, Huffman.encodedLength(value, off, len));
      Huffman.encode(out, value, off, len);
    } else {
      HpackIntegers.encode(out, 0, 7, len);
      out.writeBytes(value, off, len);
    }
  }
}
