package dev.relism.flash.http2.stream;

import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.http2.message.DataBufferPool;
import java.util.Arrays;

/** Fixed-capacity primitive stream-id table using linear-probed open addressing. */
public final class Http2StreamTable {

  @FunctionalInterface
  public interface StreamConsumer {
    void accept(Http2Stream stream);
  }

  private final int[] keys;
  private final Http2Stream[] values;
  private final int mask;
  private final int maxEntries;
  private int size;
  private Http2Stream free;
  private int created;
  private final DataBufferPool dataBuffers;

  public Http2StreamTable(int maxEntries) {
    this(
        maxEntries,
        new DataBufferPool(Http2Limits.MAX_FRAME_SIZE_LOCAL, Http2Limits.DATA_BUFFER_POOL_SIZE));
  }

  public Http2StreamTable(int maxEntries, DataBufferPool dataBuffers) {
    if (maxEntries < 1) throw new IllegalArgumentException("maxEntries must be positive");
    int capacity = 1;
    while (capacity < maxEntries * 2) capacity <<= 1;
    keys = new int[capacity];
    values = new Http2Stream[capacity];
    mask = capacity - 1;
    this.maxEntries = maxEntries;
    this.dataBuffers = dataBuffers;
  }

  public synchronized Http2Stream get(int streamId) {
    int slot = find(streamId);
    return keys[slot] == streamId ? values[slot] : null;
  }

  public synchronized void put(Http2Stream stream) {
    if (size == maxEntries) throw new IllegalStateException("stream table capacity exceeded");
    int streamId = stream.id();
    int slot = find(streamId);
    if (keys[slot] == streamId) throw new IllegalStateException("duplicate stream " + streamId);
    keys[slot] = streamId;
    values[slot] = stream;
    size++;
  }

  public synchronized Http2Stream acquire(int streamId) {
    if (size == maxEntries) return null;
    Http2Stream stream = free;
    if (stream != null) {
      free = stream.poolNext;
      stream.poolNext = null;
    } else {
      if (created == maxEntries) return null;
      stream = new Http2Stream(dataBuffers);
      created++;
    }
    stream.reset(streamId, this);
    put(stream);
    return stream;
  }

  public synchronized void release(Http2Stream stream) {
    if (!stream.claimRelease()) return;
    stream.clear();
    stream.poolNext = free;
    free = stream;
  }

  public synchronized Http2Stream remove(int streamId) {
    int slot = find(streamId);
    if (keys[slot] != streamId) return null;
    Http2Stream removed = values[slot];
    keys[slot] = 0;
    values[slot] = null;
    size--;

    int scan = (slot + 1) & mask;
    while (keys[scan] != 0) {
      int key = keys[scan];
      Http2Stream value = values[scan];
      keys[scan] = 0;
      values[scan] = null;
      size--;
      put(value);
      scan = (scan + 1) & mask;
    }
    return removed;
  }

  public synchronized void forEach(StreamConsumer consumer) {
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] != 0) consumer.accept(values[i]);
    }
  }

  public synchronized int copyValues(Http2Stream[] target) {
    int count = 0;
    for (int i = 0; i < keys.length && count < target.length; i++) {
      if (keys[i] != 0) target[count++] = values[i];
    }
    return count;
  }

  public synchronized void adjustAllSendWindows(int delta) {
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] == 0) continue;
      long adjusted = (long) values[i].sendWindow() + delta;
      if (adjusted > Integer.MAX_VALUE) {
        throw new IllegalStateException("stream window overflow");
      }
    }
    for (int i = 0; i < keys.length; i++) {
      if (keys[i] != 0) values[i].adjustSendWindow(delta);
    }
  }

  public synchronized int size() {
    return size;
  }

  public int capacity() {
    return maxEntries;
  }

  public synchronized int createdCount() {
    return created;
  }

  public synchronized int freeCount() {
    int count = 0;
    for (Http2Stream stream = free; stream != null; stream = stream.poolNext) count++;
    return count;
  }

  public synchronized void clear() {
    Arrays.fill(keys, 0);
    Arrays.fill(values, null);
    size = 0;
  }

  private int find(int streamId) {
    if (streamId <= 0) throw new IllegalArgumentException("streamId must be positive");
    int slot = mix(streamId) & mask;
    while (keys[slot] != 0 && keys[slot] != streamId) slot = (slot + 1) & mask;
    return slot;
  }

  private static int mix(int value) {
    value ^= value >>> 16;
    value *= 0x7feb352d;
    value ^= value >>> 15;
    return value;
  }
}
