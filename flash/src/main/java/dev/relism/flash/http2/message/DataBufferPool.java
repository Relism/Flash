package dev.relism.flash.http2.message;

/** Bounded connection-owned free list of frame-sized request-body buffers. */
public final class DataBufferPool {
  static final class DataBuffer {
    final byte[] bytes;
    DataBuffer next;
    int position;
    int length;
    int flowControlledBytes;

    DataBuffer(int size) {
      bytes = new byte[size];
    }

    void reset() {
      next = null;
      position = 0;
      length = 0;
      flowControlledBytes = 0;
    }
  }

  private final int bufferSize;
  private final int maxBuffers;
  private DataBuffer free;
  private int created;
  private int available;

  public DataBufferPool(int bufferSize, int maxBuffers) {
    if (bufferSize < 1 || maxBuffers < 1) {
      throw new IllegalArgumentException("bufferSize and maxBuffers must be positive");
    }
    this.bufferSize = bufferSize;
    this.maxBuffers = maxBuffers;
  }

  synchronized DataBuffer acquire() {
    DataBuffer buffer = free;
    if (buffer != null) {
      free = buffer.next;
      available--;
      buffer.reset();
      return buffer;
    }
    if (created == maxBuffers) return null;
    created++;
    return new DataBuffer(bufferSize);
  }

  synchronized void release(DataBuffer buffer) {
    buffer.reset();
    buffer.next = free;
    free = buffer;
    available++;
  }

  public synchronized int createdCount() {
    return created;
  }

  public synchronized int availableCount() {
    return available;
  }

  public int capacity() {
    return maxBuffers;
  }
}
