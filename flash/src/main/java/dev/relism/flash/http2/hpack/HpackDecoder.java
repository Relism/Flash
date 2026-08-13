package dev.relism.flash.http2.hpack;

import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.fpr.core.ByteView;

/** Stateful, allocation-free HPACK decoder for one HTTP/2 connection direction. */
public final class HpackDecoder {
  private final HpackDynamicTable dynamicTable;
  private final int maximumHeaderListSize;
  private final byte[] nameScratch = new byte[Http2Limits.MAX_HPACK_STRING_LENGTH];
  private final byte[] valueScratch = new byte[Http2Limits.MAX_HPACK_STRING_LENGTH];
  private final PooledSlice nameView = new PooledSlice();
  private final PooledSlice valueView = new PooledSlice();

  public HpackDecoder(int advertisedTableSize, int maximumHeaderListSize) {
    if (maximumHeaderListSize < 0) throw new IllegalArgumentException("negative header-list size");
    this.dynamicTable = new HpackDynamicTable(advertisedTableSize);
    this.maximumHeaderListSize = maximumHeaderListSize;
  }

  public HpackDecoder() {
    this(Http2Limits.HPACK_DYNAMIC_TABLE_SIZE_LOCAL, Http2Limits.MAX_HEADER_LIST_SIZE);
  }

  /** Decodes one complete header block. */
  public void decode(byte[] buffer, int offset, int length, HeaderSink sink) {
    if (buffer == null
        || sink == null
        || offset < 0
        || length < 0
        || offset > buffer.length - length) {
      throw new IllegalArgumentException("invalid HPACK decode arguments");
    }

    int position = offset;
    int limit = offset + length;
    boolean sawHeader = false;
    boolean oversized = false;
    long headerListSize = 0;

    while (position < limit) {
      int first = buffer[position] & 0xff;
      if ((first & 0x80) != 0) {
        long decoded = HpackIntegers.decode(buffer, position, limit, 7);
        int index = Pairs.hi(decoded);
        position = Pairs.lo(decoded);
        if (index == 0) throw Http2Exception.COMPRESSION_ERROR;
        resolve(index, nameView, valueView);
        sawHeader = true;
        headerListSize += fieldSize(nameView, valueView);
        if (headerListSize <= maximumHeaderListSize) sink.accept(nameView, valueView, false);
        else oversized = true;
        continue;
      }

      if ((first & 0x40) != 0) {
        long decoded = HpackIntegers.decode(buffer, position, limit, 6);
        int nameIndex = Pairs.hi(decoded);
        position = Pairs.lo(decoded);
        position = decodeName(buffer, position, limit, nameIndex);
        position = decodeString(buffer, position, limit, valueScratch, valueView);
        sawHeader = true;
        headerListSize += fieldSize(nameView, valueView);
        if (headerListSize <= maximumHeaderListSize) sink.accept(nameView, valueView, false);
        else oversized = true;
        dynamicTable.add(nameView, valueView);
        continue;
      }

      if ((first & 0x20) != 0) {
        if (sawHeader) throw Http2Exception.COMPRESSION_ERROR;
        long decoded = HpackIntegers.decode(buffer, position, limit, 5);
        dynamicTable.setMaximumSize(Pairs.hi(decoded));
        position = Pairs.lo(decoded);
        continue;
      }

      boolean neverIndexed = (first & 0x10) != 0;
      long decoded = HpackIntegers.decode(buffer, position, limit, 4);
      int nameIndex = Pairs.hi(decoded);
      position = Pairs.lo(decoded);
      position = decodeName(buffer, position, limit, nameIndex);
      position = decodeString(buffer, position, limit, valueScratch, valueView);
      sawHeader = true;
      headerListSize += fieldSize(nameView, valueView);
      if (headerListSize <= maximumHeaderListSize) sink.accept(nameView, valueView, neverIndexed);
      else oversized = true;
    }

    if (oversized) throw new HeaderListSizeException(headerListSize);
  }

  public HpackDynamicTable dynamicTable() {
    return dynamicTable;
  }

  private int decodeName(byte[] buffer, int position, int limit, int index) {
    if (index != 0) {
      resolveName(index, nameView);
      return position;
    }
    return decodeString(buffer, position, limit, nameScratch, nameView);
  }

  private static int decodeString(
      byte[] buffer, int position, int limit, byte[] scratch, PooledSlice output) {
    if (position >= limit) throw Http2Exception.COMPRESSION_ERROR;
    boolean huffman = (buffer[position] & 0x80) != 0;
    long decoded = HpackIntegers.decode(buffer, position, limit, 7);
    int encodedLength = Pairs.hi(decoded);
    int dataStart = Pairs.lo(decoded);
    if (encodedLength > limit - dataStart) throw Http2Exception.COMPRESSION_ERROR;
    if (huffman) {
      int decodedLength =
          Huffman.decode(buffer, dataStart, encodedLength, scratch, 0, scratch.length);
      output.reset(scratch, 0, decodedLength);
    } else {
      if (encodedLength > Http2Limits.MAX_HPACK_STRING_LENGTH)
        throw Http2Exception.COMPRESSION_ERROR;
      output.reset(buffer, dataStart, encodedLength);
    }
    return dataStart + encodedLength;
  }

  private void resolve(int index, PooledSlice name, PooledSlice value) {
    if (index <= HpackStaticTable.LENGTH) {
      byte[] staticName = HpackStaticTable.name(index);
      byte[] staticValue = HpackStaticTable.value(index);
      name.reset(staticName, 0, staticName.length);
      value.reset(staticValue, 0, staticValue.length);
      return;
    }
    dynamicTable.get(index - HpackStaticTable.LENGTH, name, value);
  }

  private void resolveName(int index, PooledSlice name) {
    if (index <= 0) throw Http2Exception.COMPRESSION_ERROR;
    if (index <= HpackStaticTable.LENGTH) {
      byte[] staticName = HpackStaticTable.name(index);
      name.reset(staticName, 0, staticName.length);
      return;
    }
    dynamicTable.get(index - HpackStaticTable.LENGTH, name, valueView);
    // An incremental-indexing representation can evict or compact the entry that supplied its
    // indexed name. Preserve the name before insertion mutates the dynamic table arena.
    System.arraycopy(name.array(), name.offset(), nameScratch, 0, name.length());
    name.reset(nameScratch, 0, name.length());
  }

  private static long fieldSize(ByteView name, ByteView value) {
    return (long) name.length() + value.length() + 32;
  }
}
