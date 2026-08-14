package dev.relism.flash.http2.hpack;

import dev.relism.flash.bytes.ArrayBackedByteView;
import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http2.Http2Exception;
import dev.relism.fpr.core.ByteView;

/**
 * Per-connection HPACK dynamic table. Entries are kept in FIFO order in a descriptor ring while
 * their bytes live in one bounded arena. The arena is compacted only when its free tail cannot hold
 * the next entry, keeping every returned view contiguous.
 */
public final class HpackDynamicTable {
  private final byte[] arena;
  private final int[] nameOffsets;
  private final int[] nameLengths;
  private final int[] valueOffsets;
  private final int[] valueLengths;
  private final int advertisedMaximum;

  private int maximumSize;
  private int currentSize;
  private int head;
  private int count;
  private int arenaEnd;

  public HpackDynamicTable(int advertisedMaximum) {
    if (advertisedMaximum < 0) throw new IllegalArgumentException("negative HPACK table size");
    this.advertisedMaximum = advertisedMaximum;
    this.maximumSize = advertisedMaximum;
    this.arena = new byte[Math.max(1, advertisedMaximum)];
    int entryCapacity = Math.max(1, advertisedMaximum / 32 + 1);
    this.nameOffsets = new int[entryCapacity];
    this.nameLengths = new int[entryCapacity];
    this.valueOffsets = new int[entryCapacity];
    this.valueLengths = new int[entryCapacity];
  }

  public int count() {
    return count;
  }

  public int size() {
    return currentSize;
  }

  public int maximumSize() {
    return maximumSize;
  }

  /** Applies an RFC 7541 §4.2 table-size update and evicts oldest entries as necessary. */
  public void setMaximumSize(int newMaximum) {
    if (newMaximum < 0 || newMaximum > advertisedMaximum) throw Http2Exception.COMPRESSION_ERROR;
    maximumSize = newMaximum;
    evictToFit(0);
    if (count == 0) arenaEnd = 0;
  }

  /** Inserts a new entry, copying its bytes before performing FIFO eviction. */
  public void add(ByteView name, ByteView value) {
    int byteLength = name.length() + value.length();
    int entrySize = byteLength + 32;
    if (entrySize > maximumSize) {
      clear();
      return;
    }

    evictToFit(entrySize);
    if (arena.length - arenaEnd < byteLength) compact();

    int slot = (head + count) % nameOffsets.length;
    nameOffsets[slot] = arenaEnd;
    nameLengths[slot] = name.length();
    copy(name, arena, arenaEnd);
    arenaEnd += name.length();
    valueOffsets[slot] = arenaEnd;
    valueLengths[slot] = value.length();
    copy(value, arena, arenaEnd);
    arenaEnd += value.length();
    count++;
    currentSize += entrySize;
  }

  /** Resolves a dynamic index where {@code 1} is the newest entry. */
  public void get(int relativeIndex, PooledSlice name, PooledSlice value) {
    if (relativeIndex < 1 || relativeIndex > count) throw Http2Exception.COMPRESSION_ERROR;
    int slot = (head + count - relativeIndex) % nameOffsets.length;
    name.reset(arena, nameOffsets[slot], nameLengths[slot]);
    value.reset(arena, valueOffsets[slot], valueLengths[slot]);
  }

  public void clear() {
    head = 0;
    count = 0;
    currentSize = 0;
    arenaEnd = 0;
  }

  private void evictToFit(int incomingSize) {
    while (count > 0 && currentSize + incomingSize > maximumSize) {
      int slot = head;
      currentSize -= nameLengths[slot] + valueLengths[slot] + 32;
      head = (head + 1) % nameOffsets.length;
      count--;
    }
    if (count == 0) arenaEnd = 0;
  }

  private void compact() {
    int destination = 0;
    for (int i = 0; i < count; i++) {
      int slot = (head + i) % nameOffsets.length;
      int nameLength = nameLengths[slot];
      int valueLength = valueLengths[slot];
      System.arraycopy(arena, nameOffsets[slot], arena, destination, nameLength);
      nameOffsets[slot] = destination;
      destination += nameLength;
      System.arraycopy(arena, valueOffsets[slot], arena, destination, valueLength);
      valueOffsets[slot] = destination;
      destination += valueLength;
    }
    arenaEnd = destination;
  }

  private static void copy(ByteView source, byte[] target, int offset) {
    if (source instanceof ArrayBackedByteView contiguous) {
      System.arraycopy(contiguous.array(), contiguous.offset(), target, offset, source.length());
      return;
    }
    for (int i = 0; i < source.length(); i++) target[offset + i] = source.byteAt(i);
  }
}
