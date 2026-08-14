package dev.relism.flash.http2;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import java.nio.charset.StandardCharsets;

/** Byte-exact client preface and immutable server startup frames, compiled once at class load. */
public final class Http2Preface {
  private static final byte[] CLIENT_PREFACE =
      "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
  private static final byte[] SERVER_SETTINGS = buildServerSettings();
  private static final byte[] SETTINGS_ACK = frame(FrameType.SETTINGS, FrameFlags.ACK, 0, 0);
  private static final byte[] INITIAL_CONNECTION_WINDOW = buildInitialConnectionWindow();

  private Http2Preface() {}

  /** Immutable client connection preface bytes. Callers must not modify the returned array. */
  public static byte[] clientPreface() {
    return CLIENT_PREFACE;
  }

  static int clientPrefaceLength() {
    return CLIENT_PREFACE.length;
  }

  static boolean matchesClientPreface(byte[] candidate) {
    if (candidate.length != CLIENT_PREFACE.length) return false;
    int different = 0;
    for (int i = 0; i < CLIENT_PREFACE.length; i++) {
      different |= candidate[i] ^ CLIENT_PREFACE[i];
    }
    return different == 0;
  }

  static byte[] serverSettings() {
    return SERVER_SETTINGS;
  }

  static byte[] settingsAck() {
    return SETTINGS_ACK;
  }

  static byte[] initialConnectionWindow() {
    return INITIAL_CONNECTION_WINDOW;
  }

  private static byte[] buildServerSettings() {
    ByteWriter bytes = new ByteWriter(64);
    FrameWriteBuffer frame = new FrameWriteBuffer(bytes);
    frame.beginFrame(FrameType.SETTINGS, 0, 0);
    setting(bytes, Http2Settings.HEADER_TABLE_SIZE, Http2Limits.HPACK_DYNAMIC_TABLE_SIZE_LOCAL);
    setting(bytes, Http2Settings.ENABLE_PUSH, 0);
    setting(bytes, Http2Settings.MAX_CONCURRENT_STREAMS, Http2Limits.MAX_CONCURRENT_STREAMS);
    setting(bytes, Http2Settings.INITIAL_WINDOW_SIZE, Http2Limits.INITIAL_WINDOW_SIZE_LOCAL);
    setting(bytes, Http2Settings.MAX_HEADER_LIST_SIZE, Http2Limits.MAX_HEADER_LIST_SIZE);
    setting(bytes, Http2Settings.ENABLE_CONNECT_PROTOCOL, 1);
    frame.endFrame();
    return copy(bytes);
  }

  private static byte[] buildInitialConnectionWindow() {
    int increment = Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL - 65_535;
    return frame(FrameType.WINDOW_UPDATE, 0, 0, increment);
  }

  private static byte[] frame(FrameType type, int flags, int streamId, int payload) {
    ByteWriter bytes = new ByteWriter(16);
    FrameWriteBuffer frame = new FrameWriteBuffer(bytes);
    frame.beginFrame(type, flags, streamId);
    if (type == FrameType.WINDOW_UPDATE) bytes.writeUInt31(payload);
    frame.endFrame();
    return copy(bytes);
  }

  private static void setting(ByteWriter bytes, int id, int value) {
    bytes.writeUInt16(id);
    bytes.writeUInt32(value);
  }

  private static byte[] copy(ByteWriter bytes) {
    byte[] result = new byte[bytes.length()];
    System.arraycopy(bytes.array(), 0, result, 0, result.length);
    return result;
  }
}
