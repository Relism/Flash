package dev.relism.flash.http2.hpack;

import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;

/** Reassembles one HEADERS/CONTINUATION sequence into a bounded contiguous connection buffer. */
public final class ContinuationAssembler {
  private final byte[] buffer;
  private int streamId;
  private int length;
  private int continuationCount;
  private boolean active;
  private boolean complete;

  public ContinuationAssembler() {
    this(Http2Limits.MAX_HEADER_LIST_SIZE);
  }

  public ContinuationAssembler(int maximumBlockSize) {
    if (maximumBlockSize <= 0) throw new IllegalArgumentException("non-positive block size");
    buffer = new byte[maximumBlockSize];
  }

  public void begin(
      int streamId, byte[] source, int offset, int fragmentLength, boolean endHeaders) {
    if (active || streamId <= 0) throw Http2Exception.PROTOCOL_ERROR;
    reset();
    this.streamId = streamId;
    append(source, offset, fragmentLength);
    complete = endHeaders;
    active = !endHeaders;
  }

  public void continuation(
      int streamId, byte[] source, int offset, int fragmentLength, boolean endHeaders) {
    if (!active || streamId != this.streamId) throw Http2Exception.PROTOCOL_ERROR;
    if (++continuationCount > Http2Limits.MAX_CONTINUATION_FRAMES_PER_BLOCK) {
      throw Http2Exception.PROTOCOL_ERROR;
    }
    append(source, offset, fragmentLength);
    complete = endHeaders;
    active = !endHeaders;
  }

  public byte[] buffer() {
    return buffer;
  }

  public int length() {
    return length;
  }

  public int streamId() {
    return streamId;
  }

  public boolean isComplete() {
    return complete;
  }

  public boolean isActive() {
    return active;
  }

  public void reset() {
    streamId = 0;
    length = 0;
    continuationCount = 0;
    active = false;
    complete = false;
  }

  private void append(byte[] source, int offset, int fragmentLength) {
    if (source == null
        || offset < 0
        || fragmentLength < 0
        || offset > source.length - fragmentLength
        || fragmentLength > buffer.length - length) {
      throw Http2Exception.COMPRESSION_ERROR;
    }
    System.arraycopy(source, offset, buffer, length, fragmentLength);
    length += fragmentLength;
  }
}
