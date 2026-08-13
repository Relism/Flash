package dev.relism.flash.http2;

import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.WriteIntent;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Reusable control-frame storage owned by one HTTP/2 connection. */
final class Http2ConnectionScratch {
  private static final int CONTROL_SLOT_COUNT =
      Http2Limits.MAX_PING_QUEUE_DEPTH + Http2Limits.MAX_SETTINGS_ACK_QUEUE_DEPTH + 8;
  private static final int CONTROL_FRAME_CAPACITY = 256;

  private final ControlIntent[] controls = new ControlIntent[CONTROL_SLOT_COUNT];
  private final AtomicInteger pingResponses = new AtomicInteger();
  private final AtomicInteger settingsAcks = new AtomicInteger();
  private final byte[] preface = new byte[Http2Preface.clientPrefaceLength()];

  Http2ConnectionScratch() {
    for (int i = 0; i < controls.length; i++) {
      controls[i] = new ControlIntent(this, CONTROL_FRAME_CAPACITY);
    }
  }

  byte[] prefaceBuffer() {
    return preface;
  }

  ControlIntent acquire(ControlKind kind) {
    AtomicInteger counter = counter(kind);
    int limit = limit(kind);
    int queued = counter.incrementAndGet();
    if (queued > limit) {
      counter.decrementAndGet();
      throw Http2Exception.of(Http2ErrorCode.ENHANCE_YOUR_CALM, kind + " queue exhausted");
    }
    for (ControlIntent intent : controls) {
      if (intent.claim(kind)) return intent;
    }
    counter.decrementAndGet();
    throw Http2Exception.of(Http2ErrorCode.ENHANCE_YOUR_CALM, "control-frame queue exhausted");
  }

  private void release(ControlIntent intent) {
    counter(intent.kind).decrementAndGet();
    intent.release();
  }

  private AtomicInteger counter(ControlKind kind) {
    return kind == ControlKind.PING ? pingResponses : settingsAcks;
  }

  private static int limit(ControlKind kind) {
    return kind == ControlKind.PING
        ? Http2Limits.MAX_PING_QUEUE_DEPTH
        : Http2Limits.MAX_SETTINGS_ACK_QUEUE_DEPTH;
  }

  enum ControlKind {
    PING,
    SETTINGS_OR_OTHER
  }

  static final class ControlIntent implements WriteIntent {
    private final Http2ConnectionScratch owner;
    private final byte[] buffer;
    private final AtomicBoolean claimed = new AtomicBoolean();
    private volatile WriteIntent next;
    private ControlKind kind;
    private int length;

    private ControlIntent(Http2ConnectionScratch owner, int capacity) {
      this.owner = owner;
      this.buffer = new byte[capacity];
    }

    private boolean claim(ControlKind kind) {
      if (!claimed.compareAndSet(false, true)) return false;
      this.kind = kind;
      this.length = 0;
      this.next = null;
      return true;
    }

    void copy(byte[] source) {
      System.arraycopy(source, 0, buffer, 0, source.length);
      length = source.length;
    }

    void frame(FrameType type, int flags, int streamId, byte[] payload, int off, int len) {
      if (9 + len > buffer.length) {
        throw new IllegalArgumentException("control frame exceeds scratch capacity");
      }
      buffer[0] = (byte) (len >>> 16);
      buffer[1] = (byte) (len >>> 8);
      buffer[2] = (byte) len;
      buffer[3] = (byte) type.code();
      buffer[4] = (byte) flags;
      writeUInt31(buffer, 5, streamId);
      System.arraycopy(payload, off, buffer, 9, len);
      length = 9 + len;
    }

    void goAway(int lastStreamId, Http2ErrorCode error, String debug) {
      int debugLength =
          Math.min(
              debug.length(),
              Math.min(Http2Limits.MAX_GOAWAY_DEBUG_DATA_LENGTH, buffer.length - 17));
      int payloadLength = 8 + debugLength;
      buffer[0] = 0;
      buffer[1] = 0;
      buffer[2] = (byte) payloadLength;
      buffer[3] = (byte) FrameType.GOAWAY.code();
      buffer[4] = 0;
      writeUInt31(buffer, 5, 0);
      writeUInt31(buffer, 9, lastStreamId);
      writeUInt32(buffer, 13, error.code());
      for (int i = 0; i < debugLength; i++) buffer[17 + i] = (byte) debug.charAt(i);
      length = 17 + debugLength;
    }

    void windowUpdate(int streamId, int increment) {
      buffer[0] = 0;
      buffer[1] = 0;
      buffer[2] = 4;
      buffer[3] = (byte) FrameType.WINDOW_UPDATE.code();
      buffer[4] = 0;
      writeUInt31(buffer, 5, streamId);
      writeUInt31(buffer, 9, increment);
      length = 13;
    }

    private static void writeUInt31(byte[] target, int off, int value) {
      writeUInt32(target, off, value & 0x7FFF_FFFF);
    }

    private static void writeUInt32(byte[] target, int off, int value) {
      target[off] = (byte) (value >>> 24);
      target[off + 1] = (byte) (value >>> 16);
      target[off + 2] = (byte) (value >>> 8);
      target[off + 3] = (byte) value;
    }

    @Override
    public byte[] buffer() {
      return buffer;
    }

    @Override
    public int offset() {
      return 0;
    }

    @Override
    public int length() {
      return length;
    }

    @Override
    public WriteIntent mpscNext() {
      return next;
    }

    @Override
    public void setMpscNext(WriteIntent next) {
      this.next = next;
    }

    @Override
    public void completed() {
      owner.release(this);
    }

    private void release() {
      next = null;
      claimed.set(false);
    }
  }
}
