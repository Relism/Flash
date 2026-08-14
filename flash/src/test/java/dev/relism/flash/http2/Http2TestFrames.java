package dev.relism.flash.http2;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class Http2TestFrames {
  static final byte[] PREFACE =
      "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private Http2TestFrames() {}

  static byte[] frame(FrameType type, int flags, int streamId, byte[] payload) {
    ByteWriter bytes = new ByteWriter(32);
    FrameWriteBuffer frame = new FrameWriteBuffer(bytes);
    frame.beginFrame(type, flags, streamId);
    bytes.writeBytes(payload);
    frame.endFrame();
    byte[] result = new byte[bytes.length()];
    System.arraycopy(bytes.array(), 0, result, 0, result.length);
    return result;
  }

  static byte[] settings(int... idValuePairs) {
    ByteWriter payload = new ByteWriter(Math.max(16, idValuePairs.length * 3));
    for (int i = 0; i < idValuePairs.length; i += 2) {
      payload.writeUInt16(idValuePairs[i]);
      payload.writeUInt32(idValuePairs[i + 1]);
    }
    byte[] body = new byte[payload.length()];
    System.arraycopy(payload.array(), 0, body, 0, body.length);
    return frame(FrameType.SETTINGS, 0, 0, body);
  }

  static byte[] concat(byte[]... parts) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] part : parts) out.writeBytes(part);
    return out.toByteArray();
  }

  static List<WireFrame> parse(byte[] bytes) {
    List<WireFrame> frames = new ArrayList<>();
    int pos = 0;
    while (pos < bytes.length) {
      int length =
          ((bytes[pos] & 0xFF) << 16) | ((bytes[pos + 1] & 0xFF) << 8) | (bytes[pos + 2] & 0xFF);
      int type = bytes[pos + 3] & 0xFF;
      int flags = bytes[pos + 4] & 0xFF;
      int streamId = readInt(bytes, pos + 5) & 0x7FFF_FFFF;
      byte[] payload = new byte[length];
      System.arraycopy(bytes, pos + 9, payload, 0, length);
      frames.add(new WireFrame(type, flags, streamId, payload));
      pos += 9 + length;
    }
    return frames;
  }

  static int readInt(byte[] bytes, int off) {
    return ((bytes[off] & 0xFF) << 24)
        | ((bytes[off + 1] & 0xFF) << 16)
        | ((bytes[off + 2] & 0xFF) << 8)
        | (bytes[off + 3] & 0xFF);
  }

  record WireFrame(int type, int flags, int streamId, byte[] payload) {}
}
