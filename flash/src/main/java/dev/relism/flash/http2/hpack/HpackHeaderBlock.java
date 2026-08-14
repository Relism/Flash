package dev.relism.flash.http2.hpack;

import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.fpr.core.ByteView;

/**
 * Reusable per-stream storage for decoded header fields. Copying at the decoder boundary makes a
 * stream independent of later HPACK dynamic-table eviction on the connection thread.
 */
public final class HpackHeaderBlock implements HeaderSink {
  private final byte[] arena;
  private final int[] nameOffsets;
  private final int[] nameLengths;
  private final int[] valueOffsets;
  private final int[] valueLengths;
  private final boolean[] neverIndexed;
  private int arenaEnd;
  private int count;

  public HpackHeaderBlock() {
    this(Http2Limits.MAX_HEADER_LIST_SIZE, Http2Limits.MAX_HEADER_LIST_SIZE / 32 + 1);
  }

  HpackHeaderBlock(int arenaCapacity, int fieldCapacity) {
    arena = new byte[arenaCapacity];
    nameOffsets = new int[fieldCapacity];
    nameLengths = new int[fieldCapacity];
    valueOffsets = new int[fieldCapacity];
    valueLengths = new int[fieldCapacity];
    neverIndexed = new boolean[fieldCapacity];
  }

  public void reset() {
    arenaEnd = 0;
    count = 0;
  }

  public int count() {
    return count;
  }

  public boolean neverIndexed(int index) {
    checkIndex(index);
    return neverIndexed[index];
  }

  public void get(int index, PooledSlice name, PooledSlice value) {
    checkIndex(index);
    name.reset(arena, nameOffsets[index], nameLengths[index]);
    value.reset(arena, valueOffsets[index], valueLengths[index]);
  }

  @Override
  public void accept(ByteView name, ByteView value, boolean sensitive) {
    int bytes = name.length() + value.length();
    if (count >= nameOffsets.length || bytes > arena.length - arenaEnd) {
      throw new IllegalStateException("decoded header block exceeds its configured storage");
    }
    nameOffsets[count] = arenaEnd;
    nameLengths[count] = name.length();
    copy(name, arenaEnd);
    arenaEnd += name.length();
    valueOffsets[count] = arenaEnd;
    valueLengths[count] = value.length();
    copy(value, arenaEnd);
    arenaEnd += value.length();
    neverIndexed[count] = sensitive;
    count++;
  }

  private void copy(ByteView source, int destination) {
    if (source instanceof ArrayBackedByteView contiguous) {
      System.arraycopy(
          contiguous.array(), contiguous.offset(), arena, destination, source.length());
      return;
    }
    for (int i = 0; i < source.length(); i++) arena[destination + i] = source.byteAt(i);
  }

  private void checkIndex(int index) {
    if (index < 0 || index >= count) throw new IndexOutOfBoundsException(index);
  }
}
