package dev.relism.flash.http2.message;

import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.message.DataBufferPool.DataBuffer;
import java.io.IOException;
import java.io.InputStream;
import dev.relism.flash.models.BodyCompletion;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Reusable request-body source fed by the connection demultiplexer. */
public final class Http2RequestBody extends InputStream implements BodyCompletion {
  @FunctionalInterface
  public interface ConsumptionListener {
    void consumed(int flowControlledBytes) throws IOException;
  }

  private final DataBufferPool pool;
  private final ReentrantLock lock = new ReentrantLock();
  private final Condition dataAvailable = lock.newCondition();
  private final byte[] oneByte = new byte[1];
  private byte[] inline;
  private DataBuffer head;
  private DataBuffer tail;
  private ConsumptionListener listener;
  private long declaredLength;
  private long received;
  private int inlinePosition;
  private int inlineFlowControlledBytes;
  private boolean inlineMode;
  private boolean finished;
  private boolean fullyRead;

  public Http2RequestBody(DataBufferPool pool) {
    this.pool = pool;
  }

  public void begin(long declaredLength, boolean inlineMode, ConsumptionListener listener) {
    releaseQueued();
    this.declaredLength = declaredLength;
    this.inlineMode = inlineMode;
    this.listener = listener;
    received = 0;
    inlinePosition = 0;
    inlineFlowControlledBytes = 0;
    finished = false;
    fullyRead = false;
    if (inlineMode && inline == null) inline = new byte[Http2Limits.INLINE_BODY_THRESHOLD];
  }

  public void offer(
      int streamId, byte[] source, int offset, int length, int flowControlledBytes) {
    long next = received + length;
    if (next > Http2Limits.MAX_REQUEST_BODY_SIZE) {
      throw new Http2StreamException(
          streamId, Http2ErrorCode.PROTOCOL_ERROR, "request body exceeds configured limit");
    }
    if (declaredLength >= 0 && next > declaredLength) {
      throw new Http2StreamException(
          streamId, Http2ErrorCode.PROTOCOL_ERROR, "request body exceeds content-length");
    }
    if (length == 0) {
      notifyConsumed(flowControlledBytes);
      return;
    }
    if (inlineMode) {
      if (next > inline.length) {
        throw new Http2StreamException(
            streamId, Http2ErrorCode.PROTOCOL_ERROR, "inline request body exceeded its bound");
      }
      System.arraycopy(source, offset, inline, (int) received, length);
      received = next;
      inlineFlowControlledBytes += flowControlledBytes;
      return;
    }

    lock.lock();
    try {
      int remaining = length;
      int sourcePosition = offset;
      while (remaining > 0) {
        if (tail == null || tail.length == tail.bytes.length) {
          DataBuffer buffer = pool.acquire();
          if (buffer == null) {
            throw new Http2StreamException(
                streamId,
                Http2ErrorCode.ENHANCE_YOUR_CALM,
                "request body buffer pool exhausted");
          }
          if (tail == null) head = buffer;
          else tail.next = buffer;
          tail = buffer;
        }
        int copied = Math.min(remaining, tail.bytes.length - tail.length);
        System.arraycopy(source, sourcePosition, tail.bytes, tail.length, copied);
        tail.length += copied;
        sourcePosition += copied;
        remaining -= copied;
      }
      tail.flowControlledBytes += flowControlledBytes;
      received = next;
      dataAvailable.signal();
    } finally {
      lock.unlock();
    }
  }

  public void finish(int streamId) {
    if (declaredLength >= 0 && received != declaredLength) {
      throw new Http2StreamException(
          streamId,
          Http2ErrorCode.PROTOCOL_ERROR,
          "content-length does not match received DATA bytes");
    }
    lock.lock();
    try {
      finished = true;
      dataAvailable.signalAll();
    } finally {
      lock.unlock();
    }
  }

  public int cancel() {
    int discarded;
    lock.lock();
    try {
      finished = true;
      discarded = inlineFlowControlledBytes + releaseQueuedLocked();
      inlineFlowControlledBytes = 0;
      dataAvailable.signalAll();
    } finally {
      lock.unlock();
    }
    return discarded;
  }

  public long declaredLength() {
    return declaredLength;
  }

  @Override
  public int read() throws IOException {
    int count = read(oneByte, 0, 1);
    return count < 0 ? -1 : oneByte[0] & 0xff;
  }

  @Override
  public int read(byte[] target, int offset, int length) throws IOException {
    if (length == 0) return 0;
    if (inlineMode) return readInline(target, offset, length);

    DataBuffer consumed = null;
    int copied;
    int flowControlled = 0;
    lock.lock();
    try {
      while (head == null && !finished) {
        try {
          dataAvailable.await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IOException("interrupted while waiting for request DATA", interrupted);
        }
      }
      if (head == null) {
        fullyRead = true;
        return -1;
      }
      DataBuffer buffer = head;
      copied = Math.min(length, buffer.length - buffer.position);
      System.arraycopy(buffer.bytes, buffer.position, target, offset, copied);
      buffer.position += copied;
      if (buffer.position == buffer.length) {
        head = buffer.next;
        if (head == null) tail = null;
        flowControlled = buffer.flowControlledBytes;
        consumed = buffer;
      }
    } finally {
      lock.unlock();
    }
    if (consumed != null) {
      pool.release(consumed);
      notifyConsumed(flowControlled);
    }
    return copied;
  }

  private int readInline(byte[] target, int offset, int length) throws IOException {
    if (!finished) {
      throw new IOException("inline request body is not complete");
    }
    if (inlinePosition == received) {
      fullyRead = true;
      return -1;
    }
    int copied = (int) Math.min(length, received - inlinePosition);
    System.arraycopy(inline, inlinePosition, target, offset, copied);
    inlinePosition += copied;
    if (inlinePosition == received && inlineFlowControlledBytes != 0) {
      int flowControlled = inlineFlowControlledBytes;
      inlineFlowControlledBytes = 0;
      notifyConsumed(flowControlled);
    }
    return copied;
  }

  @Override
  public boolean fullyRead() {
    return fullyRead || (finished && received == 0);
  }

  private void notifyConsumed(int bytes) {
    if (bytes == 0 || listener == null) return;
    try {
      listener.consumed(bytes);
    } catch (IOException failure) {
      cancel();
      throw new IllegalStateException("failed to update request flow-control window", failure);
    }
  }

  private void releaseQueued() {
    lock.lock();
    try {
      releaseQueuedLocked();
    } finally {
      lock.unlock();
    }
  }

  private int releaseQueuedLocked() {
    int flowControlled = 0;
    while (head != null) {
      DataBuffer released = head;
      head = released.next;
      flowControlled += released.flowControlledBytes;
      pool.release(released);
    }
    tail = null;
    return flowControlled;
  }
}
