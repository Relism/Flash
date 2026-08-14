package dev.relism.flash.http2;

import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameHeader;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.Padding;
import dev.relism.flash.http2.hpack.ContinuationAssembler;
import dev.relism.flash.http2.hpack.HeaderListSizeException;
import dev.relism.flash.http2.hpack.HeaderSink;
import dev.relism.flash.http2.hpack.HpackDecoder;

/** Composes frame fragment extraction, CONTINUATION assembly and HPACK decoding. */
final class Http2HeaderBlockDecoder {
  private static final int PRIORITY_FIELDS_LENGTH = 5;

  private final ContinuationAssembler assembler = new ContinuationAssembler();
  private final HpackDecoder decoder = new HpackDecoder();
  private final long assemblyTimeoutNanos;
  private boolean endStream;
  private long assemblyStartedNanos;

  Http2HeaderBlockDecoder() {
    this(Http2Limits.HEADER_BLOCK_ASSEMBLY_TIMEOUT_MS);
  }

  Http2HeaderBlockDecoder(long assemblyTimeoutMillis) {
    if (assemblyTimeoutMillis <= 0) throw new IllegalArgumentException("non-positive timeout");
    assemblyTimeoutNanos = assemblyTimeoutMillis * 1_000_000L;
  }

  boolean insideHeaderBlock() {
    return assembler.isActive();
  }

  /** Accepts HEADERS or CONTINUATION and returns true when a complete block was decoded. */
  boolean accept(FrameHeader frame, HeaderSink sink) {
    checkTimeout();
    if (assembler.isActive() && frame.type() != FrameType.CONTINUATION) {
      throw Http2Exception.PROTOCOL_ERROR;
    }
    if (frame.type() == FrameType.HEADERS) {
      begin(frame);
    } else if (frame.type() == FrameType.CONTINUATION) {
      assembler.continuation(
          frame.streamId(),
          frame.buffer(),
          frame.payloadOffset(),
          frame.length(),
          FrameFlags.isEndHeaders(frame.flags()));
    } else {
      return false;
    }
    if (!assembler.isComplete()) return false;

    try {
      decoder.decode(assembler.buffer(), 0, assembler.length(), sink);
    } catch (HeaderListSizeException tooLarge) {
      int streamId = assembler.streamId();
      assembler.reset();
      throw new Http2StreamException(
          streamId, Http2ErrorCode.ENHANCE_YOUR_CALM, tooLarge.getMessage());
    }
    assembler.reset();
    assemblyStartedNanos = 0;
    return true;
  }

  void checkTimeout() {
    if (assembler.isActive()
        && System.nanoTime() - assemblyStartedNanos >= assemblyTimeoutNanos) {
      assembler.reset();
      assemblyStartedNanos = 0;
      throw Http2Exception.of(Http2ErrorCode.ENHANCE_YOUR_CALM,
          "header block assembly timeout");
    }
  }

  boolean endStream() {
    return endStream;
  }

  private void begin(FrameHeader frame) {
    assemblyStartedNanos = System.nanoTime();
    endStream = FrameFlags.isEndStream(frame.flags());
    long unpadded =
        Padding.unpad(
            frame.buffer(),
            frame.payloadOffset(),
            frame.length(),
            FrameFlags.isPadded(frame.flags()));
    int fragmentOffset = Pairs.hi(unpadded);
    int fragmentLength = Pairs.lo(unpadded);
    if (FrameFlags.hasPriority(frame.flags())) {
      if (fragmentLength < PRIORITY_FIELDS_LENGTH) throw Http2Exception.FRAME_SIZE_ERROR;
      int dependency =
          ((frame.buffer()[fragmentOffset] & 0x7f) << 24)
              | ((frame.buffer()[fragmentOffset + 1] & 0xff) << 16)
              | ((frame.buffer()[fragmentOffset + 2] & 0xff) << 8)
              | (frame.buffer()[fragmentOffset + 3] & 0xff);
      if (dependency == frame.streamId()) {
        throw new Http2StreamException(
            frame.streamId(), Http2ErrorCode.PROTOCOL_ERROR, "stream depends on itself");
      }
      fragmentOffset += PRIORITY_FIELDS_LENGTH;
      fragmentLength -= PRIORITY_FIELDS_LENGTH;
    }
    assembler.begin(
        frame.streamId(),
        frame.buffer(),
        fragmentOffset,
        fragmentLength,
        FrameFlags.isEndHeaders(frame.flags()));
  }
}
