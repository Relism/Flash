package dev.relism.flash.http2.frame;

import dev.relism.flash.bytes.ByteWriter;

/**
 * Serializes HTTP/2 frames into a {@link ByteWriter} scratch buffer with the standard
 * length-back-patching technique: {@link #beginFrame} writes a 9-byte header with a placeholder
 * length, the caller writes the payload directly through {@link #writer()} (the same
 * {@link ByteWriter}), and {@link #endFrame} rewrites the length once it is known — the payload
 * size is rarely known before it is serialized (an HPACK-encoded header block, in particular,
 * has no cheap way to be measured in advance).
 *
 * issues one bulk {@code write}, rather than streaming bytes as they are produced: streaming
 * would require knowing the length <em>before</em> the first byte goes out, which back-patching
 * deliberately avoids needing.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * FrameWriteBuffer out = new FrameWriteBuffer(new ByteWriter(4096));
 * out.beginFrame(FrameType.SETTINGS, 0, 0);
 * out.writer().writeUInt16(SETTINGS_MAX_CONCURRENT_STREAMS);
 * out.writer().writeUInt32(100);
 * out.endFrame();
 * // out.writer().array()[0, out.writer().length()) now holds one complete, correctly-lengthed frame
 * }</pre>
 *
 * <h3>Multiple frames, one buffer</h3>
 * {@link #beginFrame}/{@link #endFrame} pairs may be repeated on the same instance without a
 * {@link ByteWriter#reset()} between them — each pair appends one more complete frame after
 * whatever was already written, which is exactly what {@link Http2FrameWriter#write} wants for a
 * single bulk write covering several frames (e.g. HEADERS followed immediately by its first
 * DATA frame).
 *
 * <h3>Thread-safety</h3>
 * Not thread-safe — exactly one writer at a time, the same convention every other per-connection
 * scratch object in this codebase follows.
 */
public final class FrameWriteBuffer {
    private final ByteWriter writer;
    private int headerStart = -1;

    public FrameWriteBuffer(ByteWriter writer) {
        this.writer = writer;
    }

    /** The underlying {@link ByteWriter} — write the frame's payload directly through this between {@link #beginFrame} and {@link #endFrame}. */
    public ByteWriter writer() {
        return writer;
    }

    /** Writes a 9-byte frame header with a placeholder length, to be filled in by {@link #endFrame}. */
    public void beginFrame(FrameType type, int flags, int streamId) {
        if (headerStart != -1) {
            throw new IllegalStateException("beginFrame() called again before the previous frame's endFrame()");
        }
        headerStart = writer.length();
        writer.writeUInt24(0); // length placeholder
        writer.writeByte((byte) type.code());
        writer.writeByte((byte) flags);
        writer.writeUInt31(streamId);
    }

    /** Back-patches the length field written by {@link #beginFrame} now that the payload's size is known. */
    public void endFrame() {
        if (headerStart == -1) {
            throw new IllegalStateException("endFrame() called without a matching beginFrame()");
        }
        int payloadLength = writer.length() - (headerStart + 9);
        byte[] buf = writer.array();
        buf[headerStart] = (byte) (payloadLength >>> 16);
        buf[headerStart + 1] = (byte) (payloadLength >>> 8);
        buf[headerStart + 2] = (byte) payloadLength;
        headerStart = -1;
    }
}
