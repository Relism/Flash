package dev.relism.flash.http2.message;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.DateHeader;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import dev.relism.flash.http2.frame.WriteIntent;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.ResponseSerializer;

/**
 * Reusable per-stream HTTP/2 response serializer. It prepares a complete small response outside the
 * connection write lock and exposes it as one {@link WriteIntent}.
 */
public final class Http2ResponseWriter implements WriteIntent, ResponseSerializer.FieldConsumer {
  @FunctionalInterface
  public interface Completion {
    void responseWriteCompleted();
  }

  private static final int STATUS_NAME_LENGTH = 7;
  private static final int CONTENT_LENGTH_NAME_LENGTH = 14;

  private final ByteWriter headerBlock;
  private final ByteWriter output;
  private final FrameWriteBuffer frames;
  private final byte[] decimalScratch = new byte[10];
  private WriteIntent next;
  private boolean huffmanDynamicValues;
  private int streamId;
  private long headerListSize;
  private long maxHeaderListSize;
  private Completion completion;

  public Http2ResponseWriter() {
    this(1024, 2048);
  }

  public Http2ResponseWriter(int initialHeaderCapacity, int initialOutputCapacity) {
    headerBlock = new ByteWriter(initialHeaderCapacity);
    output = new ByteWriter(initialOutputCapacity);
    frames = new FrameWriteBuffer(output);
  }

  public void completion(Completion completion) {
    this.completion = completion;
  }

  /**
   * Prepares a non-streaming response. Returns {@code false} when the body needs the deferred DATA
   * flow-control path implemented by the stream scheduler.
   */
  public boolean prepare(
      Response response,
      int streamId,
      boolean headRequest,
      boolean sendDate,
      boolean sendContentLength,
      boolean huffmanDynamicValues,
      boolean emitTableSizeUpdate,
      int maxFrameSize,
      long maxHeaderListSize,
      int availableFlowWindow) {
    if (streamId <= 0) throw new IllegalArgumentException("streamId must be positive");
    if (maxFrameSize <= 0) throw new IllegalArgumentException("maxFrameSize must be positive");
    if (response.isStreaming()) {
      throw new IllegalArgumentException("streaming responses use the HTTP/2 DATA scheduler");
    }

    headerBlock.reset();
    output.reset();

    byte[] body = response.getBody();
    int bodyLength = body == null ? 0 : body.length;
    int statusCode = response.getStatusCode();
    boolean bodyForbidden =
        statusCode == 204 || statusCode == 304 || (statusCode >= 100 && statusCode < 200);
    boolean suppressBody = headRequest || bodyForbidden;
    if (!suppressBody
        && bodyLength > 0
        && (bodyLength > maxFrameSize || bodyLength > availableFlowWindow)) {
      return false;
    }

    this.streamId = streamId;
    this.huffmanDynamicValues = huffmanDynamicValues;
    this.maxHeaderListSize = maxHeaderListSize;
    headerListSize = 0;
    next = null;

    if (emitTableSizeUpdate) HpackEncoder.writeDynamicTableSizeUpdateZero(headerBlock);
    writeStatus(statusCode);
    writeContentType(response.getContentType());

    if (sendDate) {
      addHeaderListSize(4, 29);
      headerBlock.writeBytes(DateHeader.hpackBytes());
    }
    if (sendContentLength && !bodyForbidden) {
      addHeaderListSize(CONTENT_LENGTH_NAME_LENGTH, decimalLength(bodyLength));
      writeDecimalLiteral(28, bodyLength);
    }

    ResponseSerializer.forEachCustomField(response, this);
    writeHeaderFrames(maxFrameSize, suppressBody || bodyLength == 0);
    if (!suppressBody && bodyLength > 0) {
      frames.beginFrame(FrameType.DATA, FrameFlags.END_STREAM, streamId);
      output.writeBytes(body);
      frames.endFrame();
    }
    return true;
  }

  @Override
  public void accept(
      byte[] name, int nameOff, int nameLen, byte[] value, int valueOff, int valueLen) {
    if (isForbidden(name, nameOff, nameLen)) return;
    addHeaderListSize(nameLen, valueLen);
    HpackEncoder.writeLiteral(
        headerBlock, name, nameOff, nameLen, value, valueOff, valueLen, huffmanDynamicValues);
  }

  private void writeStatus(int statusCode) {
    addHeaderListSize(STATUS_NAME_LENGTH, 3);
    byte[] precompiled = HttpStatus.hpackBytesForCode(statusCode);
    if (precompiled != null) {
      headerBlock.writeBytes(precompiled);
      return;
    }
    if (statusCode < 100 || statusCode > 999) {
      throw new Http2StreamException(
          streamId, Http2ErrorCode.INTERNAL_ERROR, "HTTP status must contain three digits");
    }
    writeDecimalLiteral(8, statusCode);
  }

  private void writeContentType(byte[] contentType) {
    if (contentType == null || contentType.length == 0) return;
    addHeaderListSize(12, contentType.length);
    byte[] precompiled = ContentType.hpackBytesFor(contentType);
    if (precompiled != null) {
      headerBlock.writeBytes(precompiled);
    } else {
      HpackEncoder.writeLiteralWithNameIndex(headerBlock, 31, contentType, huffmanDynamicValues);
    }
  }

  private void writeDecimalLiteral(int nameIndex, int value) {
    int length = decimalLength(value);
    int offset = decimalScratch.length - length;
    int current = value;
    for (int i = decimalScratch.length - 1; i >= offset; i--) {
      decimalScratch[i] = (byte) ('0' + current % 10);
      current /= 10;
    }
    HpackEncoder.writeLiteralWithNameIndex(
        headerBlock, nameIndex, decimalScratch, offset, length, huffmanDynamicValues);
  }

  private void writeHeaderFrames(int maxFrameSize, boolean endStream) {
    int remaining = headerBlock.length();
    int offset = 0;
    boolean first = true;
    do {
      int fragment = Math.min(remaining, maxFrameSize);
      boolean last = fragment == remaining;
      int flags = last ? FrameFlags.END_HEADERS : 0;
      if (first && endStream) flags |= FrameFlags.END_STREAM;
      frames.beginFrame(first ? FrameType.HEADERS : FrameType.CONTINUATION, flags, streamId);
      output.writeBytes(headerBlock.array(), offset, fragment);
      frames.endFrame();
      offset += fragment;
      remaining -= fragment;
      first = false;
    } while (remaining > 0);
  }

  private void addHeaderListSize(int nameLength, int valueLength) {
    headerListSize += nameLength + valueLength + 32L;
    if (headerListSize > maxHeaderListSize) {
      throw new Http2StreamException(
          streamId,
          Http2ErrorCode.INTERNAL_ERROR,
          "response header list exceeds peer limit " + maxHeaderListSize);
    }
  }

  private static boolean isForbidden(byte[] name, int off, int len) {
    return equalsAscii(name, off, len, "connection")
        || equalsAscii(name, off, len, "keep-alive")
        || equalsAscii(name, off, len, "proxy-connection")
        || equalsAscii(name, off, len, "transfer-encoding")
        || equalsAscii(name, off, len, "upgrade");
  }

  private static boolean equalsAscii(byte[] bytes, int off, int len, String expected) {
    if (len != expected.length()) return false;
    for (int i = 0; i < len; i++) {
      int actual = bytes[off + i] & 0xff;
      if (actual >= 'A' && actual <= 'Z') actual += 32;
      if (actual != expected.charAt(i)) return false;
    }
    return true;
  }

  private static int decimalLength(int value) {
    if (value < 10) return 1;
    if (value < 100) return 2;
    if (value < 1000) return 3;
    if (value < 10000) return 4;
    if (value < 100000) return 5;
    if (value < 1000000) return 6;
    if (value < 10000000) return 7;
    if (value < 100000000) return 8;
    if (value < 1000000000) return 9;
    return 10;
  }

  @Override
  public byte[] buffer() {
    return output.array();
  }

  @Override
  public int offset() {
    return 0;
  }

  @Override
  public int length() {
    return output.length();
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
    if (completion != null) completion.responseWriteCompleted();
  }
}
