package dev.relism.flash.http2.message;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.DateHeader;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import dev.relism.flash.http2.frame.WriteIntent;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.ResponseSerializer;
import java.io.IOException;
import java.io.InputStream;

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
  private final byte[] decimalScratch = new byte[20];
  private final byte[] relay = new byte[Http2Limits.MAX_FRAME_SIZE_LOCAL];
  private WriteIntent next;
  private boolean huffmanDynamicValues;
  private int streamId;
  private long headerListSize;
  private long maxHeaderListSize;
  private Completion completion;
  private byte[] fixedBody;
  private InputStream streamBody;
  private long bodyRemaining;
  private int fixedPosition;
  private boolean unknownLength;
  private boolean pushBody;
  private boolean finished;
  private boolean headersInBatch;
  private boolean endStreamInBatch;
  private int dataBytesInBatch;
  private boolean trailerHeadersInBatch;
  private Response response;

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
    this.response = response;
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
    boolean hasTrailers = response.hasTrailers() && !suppressBody;
    writeHeaderFrames(maxFrameSize, suppressBody || (bodyLength == 0 && !hasTrailers));
    if (!suppressBody && bodyLength > 0) {
      frames.beginFrame(FrameType.DATA, hasTrailers ? 0 : FrameFlags.END_STREAM, streamId);
      output.writeBytes(body);
      frames.endFrame();
    }
    if (hasTrailers) appendTrailers(maxFrameSize);
    return true;
  }

  /** Starts a response whose DATA may span multiple flow-control windows. */
  public int startFlowControlled(
      Response response,
      int streamId,
      boolean headRequest,
      boolean sendDate,
      boolean sendContentLength,
      boolean huffmanDynamicValues,
      boolean emitTableSizeUpdate,
      int maxFrameSize,
      long maxHeaderListSize,
      int availableFlowWindow)
      throws IOException {
    if (streamId <= 0) throw new IllegalArgumentException("streamId must be positive");
    if (maxFrameSize <= 0 || availableFlowWindow < 0) {
      throw new IllegalArgumentException("frame size must be positive and flow window non-negative");
    }
    headerBlock.reset();
    output.reset();
    this.streamId = streamId;
    this.huffmanDynamicValues = huffmanDynamicValues;
    this.maxHeaderListSize = maxHeaderListSize;
    headerListSize = 0;
    next = null;
    headersInBatch = true;
    endStreamInBatch = false;
    dataBytesInBatch = 0;
    trailerHeadersInBatch = false;
    this.response = response;
    fixedPosition = 0;
    fixedBody = response.isStreaming() ? null : response.getBody();
    streamBody = response.isStreaming() ? response.getStream() : null;
    unknownLength = response.isStreaming() && response.isChunked();
    pushBody = response.isPushStreaming();
    if (response.isStreaming() && !unknownLength && response.getStreamLength() < 0) {
      throw new IllegalArgumentException("known response stream length must not be negative");
    }
    bodyRemaining =
        response.isStreaming()
            ? (unknownLength ? -1 : response.getStreamLength())
            : (fixedBody == null ? 0 : fixedBody.length);
    long representationLength = bodyRemaining;
    boolean representationUnknownLength = unknownLength;

    int statusCode = response.getStatusCode();
    boolean bodyForbidden =
        statusCode == 204 || statusCode == 304 || (statusCode >= 100 && statusCode < 200);
    if (headRequest || bodyForbidden) {
      fixedBody = null;
      streamBody = null;
      unknownLength = false;
      bodyRemaining = 0;
    }

    if (emitTableSizeUpdate) HpackEncoder.writeDynamicTableSizeUpdateZero(headerBlock);
    writeStatus(statusCode);
    writeContentType(response.getContentType());
    if (sendDate) {
      addHeaderListSize(4, 29);
      headerBlock.writeBytes(DateHeader.hpackBytes());
    }
    if (sendContentLength && !bodyForbidden && !representationUnknownLength) {
      addHeaderListSize(CONTENT_LENGTH_NAME_LENGTH, decimalLength(representationLength));
      writeDecimalLiteral(28, representationLength);
    }
    ResponseSerializer.forEachCustomField(response, this);

    boolean hasBody = unknownLength || bodyRemaining > 0;
    boolean hasTrailers = !headRequest && !bodyForbidden && response.hasTrailers();
    writeHeaderFrames(maxFrameSize, !hasBody && !hasTrailers);
    finished = !hasBody && !hasTrailers;
    if (!hasBody && hasTrailers) {
      appendTrailers(maxFrameSize);
      finished = true;
      endStreamInBatch = true;
    }
    if (hasBody && availableFlowWindow > 0) {
      appendData(maxFrameSize, availableFlowWindow);
    }
    return dataBytesInBatch;
  }

  /** Serializes the next DATA batch after a WINDOW_UPDATE or previous write completion. */
  public int resume(int maxFrameSize, int availableFlowWindow) throws IOException {
    if (finished || availableFlowWindow <= 0) return 0;
    output.reset();
    next = null;
    headersInBatch = false;
    endStreamInBatch = false;
    dataBytesInBatch = 0;
    trailerHeadersInBatch = false;
    appendData(maxFrameSize, availableFlowWindow);
    return dataBytesInBatch;
  }

  private void appendData(int maxFrameSize, int availableFlowWindow) throws IOException {
    int target = Math.min(relay.length, Math.min(maxFrameSize, availableFlowWindow));
    int count;
    boolean end;
    if (fixedBody != null) {
      count = (int) Math.min(target, bodyRemaining);
      boolean finalData = count == bodyRemaining;
      boolean trailersFollow = finalData && response.hasTrailers();
      frames.beginFrame(
          FrameType.DATA, finalData && !trailersFollow ? FrameFlags.END_STREAM : 0, streamId);
      output.writeBytes(fixedBody, fixedPosition, count);
      frames.endFrame();
      fixedPosition += count;
      bodyRemaining -= count;
      end = bodyRemaining == 0;
    } else {
      int limit = unknownLength ? target : (int) Math.min(target, bodyRemaining);
      count = 0;
      boolean eof = false;
      if (pushBody) {
        int read = streamBody.read(relay, 0, limit);
        if (read < 0) eof = true;
        else count = read;
      } else {
        while (count < limit) {
          int read = streamBody.read(relay, count, limit - count);
          if (read < 0) {
            eof = true;
            break;
          }
          if (read == 0) {
            int one = streamBody.read();
            if (one < 0) {
              eof = true;
              break;
            }
            relay[count++] = (byte) one;
          } else {
            count += read;
          }
        }
      }
      if (!unknownLength) {
        bodyRemaining -= count;
        if (eof && bodyRemaining != 0) {
          throw new IOException("streaming response ended before its declared length");
        }
      }
      end = unknownLength ? eof : bodyRemaining == 0;
      boolean trailersFollow = end && response.hasTrailers();
      if (count != 0 || !trailersFollow) {
        frames.beginFrame(FrameType.DATA, end && !trailersFollow ? FrameFlags.END_STREAM : 0, streamId);
        output.writeBytes(relay, 0, count);
        frames.endFrame();
      }
    }
    dataBytesInBatch = count;
    if (end && response.hasTrailers()) {
      appendTrailers(maxFrameSize);
      endStreamInBatch = true;
    } else {
      endStreamInBatch = end;
    }
    finished = end;
  }

  private void appendTrailers(int maxFrameSize) {
    headerBlock.reset();
    headerListSize = 0;
    ResponseSerializer.forEachTrailerField(response, this);
    writeHeaderFrames(maxFrameSize, true);
    trailerHeadersInBatch = true;
  }

  public boolean finished() {
    return finished;
  }

  public boolean headersInBatch() {
    return headersInBatch;
  }

  public boolean endStreamInBatch() {
    return endStreamInBatch;
  }

  public int dataBytesInBatch() {
    return dataBytesInBatch;
  }

  public boolean trailerHeadersInBatch() {
    return trailerHeadersInBatch;
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

  private void writeDecimalLiteral(int nameIndex, long value) {
    int length = decimalLength(value);
    int offset = decimalScratch.length - length;
    long current = value;
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

  private static int decimalLength(long value) {
    int length = 1;
    while (value >= 10) {
      value /= 10;
      length++;
    }
    return length;
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
