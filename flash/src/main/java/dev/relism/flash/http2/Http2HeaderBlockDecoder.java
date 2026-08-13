package dev.relism.flash.http2;

import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameHeader;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.Padding;
import dev.relism.flash.http2.hpack.ContinuationAssembler;
import dev.relism.flash.http2.hpack.HeaderListSizeException;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;

/** Composes frame fragment extraction, CONTINUATION assembly and HPACK decoding. */
final class Http2HeaderBlockDecoder {
  private static final int PRIORITY_FIELDS_LENGTH = 5;

  private final ContinuationAssembler assembler = new ContinuationAssembler();
  private final HpackDecoder decoder = new HpackDecoder();
  private final HpackHeaderBlock headers = new HpackHeaderBlock();

  boolean insideHeaderBlock() {
    return assembler.isActive();
  }

  /** Accepts HEADERS or CONTINUATION and returns true when a complete block was decoded. */
  boolean accept(FrameHeader frame) {
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

    headers.reset();
    try {
      decoder.decode(assembler.buffer(), 0, assembler.length(), headers);
    } catch (HeaderListSizeException tooLarge) {
      int streamId = assembler.streamId();
      assembler.reset();
      throw new Http2StreamException(
          streamId, Http2ErrorCode.ENHANCE_YOUR_CALM, tooLarge.getMessage());
    }
    assembler.reset();
    return true;
  }

  private void begin(FrameHeader frame) {
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
