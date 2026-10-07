package dev.relism.flash.http2;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameWriteBuffer;
import dev.relism.flash.http2.hpack.HpackDecoder;
import dev.relism.flash.http2.hpack.HpackEncoder;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/** Minimal RFC 8441 peer used only by the live WebSocket-over-h2 tests. */
final class H2WebSocketTestClient implements Closeable {
  private static final int WINDOW = 2 * 1024 * 1024;

  private final Socket socket;
  private final InputStream input;
  private final OutputStream output;
  private final ByteArrayOutputStream responseData = new ByteArrayOutputStream();
  private int connectionWindow = 65_535;
  private int streamWindow = 65_535;
  private int peerMaxFrame = 16_384;
  private boolean connectProtocolAdvertised;
  private boolean responseEnded;

  H2WebSocketTestClient(String host, int port, String path) throws Exception {
    socket = new Socket(host, port);
    socket.setSoTimeout(5_000);
    input = socket.getInputStream();
    output = socket.getOutputStream();
    writePreface();
    awaitSettings();
    writeConnect(host + ":" + port, path);
    int status = awaitStatus();
    if (status != 200) throw new IOException("extended CONNECT returned " + status);
  }

  boolean connectProtocolAdvertised() {
    return connectProtocolAdvertised;
  }

  void sendText(String value) throws Exception {
    sendWebSocketFrame(true, (byte) 1, value.getBytes(StandardCharsets.UTF_8), false);
  }

  void sendFragmentedText(String first, String second) throws Exception {
    sendWebSocketFrame(
        false, (byte) 1, first.getBytes(StandardCharsets.UTF_8), false);
    sendWebSocketFrame(
        true, (byte) 0, second.getBytes(StandardCharsets.UTF_8), false);
  }

  void sendBinary(byte[] value) throws Exception {
    sendWebSocketFrame(true, (byte) 2, value, false);
  }

  byte[] readMessage(byte expectedOpcode) throws Exception {
    responseData.reset();
    while (true) {
      readAndHandleFrame();
      byte[] bytes = responseData.toByteArray();
      if (bytes.length < 2) continue;
      int opcode = bytes[0] & 0x0f;
      int marker = bytes[1] & 0x7f;
      int headerLength;
      long payloadLength;
      if (marker < 126) {
        headerLength = 2;
        payloadLength = marker;
      } else if (marker == 126) {
        if (bytes.length < 4) continue;
        headerLength = 4;
        payloadLength = ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
      } else {
        if (bytes.length < 10) continue;
        headerLength = 10;
        payloadLength = 0;
        for (int i = 2; i < 10; i++) payloadLength = (payloadLength << 8) | (bytes[i] & 0xffL);
      }
      if (payloadLength > Integer.MAX_VALUE || bytes.length < headerLength + payloadLength) {
        continue;
      }
      if (opcode != expectedOpcode) throw new IOException("unexpected WebSocket opcode " + opcode);
      byte[] payload = new byte[(int) payloadLength];
      System.arraycopy(bytes, headerLength, payload, 0, payload.length);
      return payload;
    }
  }

  void closeGracefully() throws Exception {
    sendWebSocketFrame(true, (byte) 8, new byte[] {3, (byte) 232}, true);
    while (!responseEnded) readAndHandleFrame();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }

  private void writePreface() throws IOException {
    output.write(Http2Preface.clientPreface());
    ByteWriter bytes = new ByteWriter(64);
    FrameWriteBuffer frames = new FrameWriteBuffer(bytes);
    frames.beginFrame(FrameType.SETTINGS, 0, 0);
    bytes.writeUInt16(Http2Settings.ENABLE_PUSH);
    bytes.writeUInt32(0);
    bytes.writeUInt16(Http2Settings.INITIAL_WINDOW_SIZE);
    bytes.writeUInt32(WINDOW);
    frames.endFrame();
    frames.beginFrame(FrameType.WINDOW_UPDATE, 0, 0);
    bytes.writeUInt31(WINDOW - 65_535);
    frames.endFrame();
    output.write(bytes.array(), 0, bytes.length());
  }

  private void awaitSettings() throws Exception {
    while (!connectProtocolAdvertised) {
      WireFrame frame = readFrame();
      if (frame.type == FrameType.SETTINGS.code() && (frame.flags & FrameFlags.ACK) == 0) {
        for (int offset = 0; offset < frame.payload.length; offset += 6) {
          int id = ((frame.payload[offset] & 0xff) << 8) | (frame.payload[offset + 1] & 0xff);
          int value = readInt(frame.payload, offset + 2);
          if (id == Http2Settings.ENABLE_CONNECT_PROTOCOL && value == 1) {
            connectProtocolAdvertised = true;
          } else if (id == Http2Settings.INITIAL_WINDOW_SIZE) {
            streamWindow = value;
          } else if (id == Http2Settings.MAX_FRAME_SIZE) {
            peerMaxFrame = value;
          }
        }
        writeEmpty(FrameType.SETTINGS, FrameFlags.ACK, 0);
      } else {
        handle(frame);
      }
    }
  }

  private void writeConnect(String authority, String path) throws IOException {
    ByteWriter bytes = new ByteWriter(256);
    FrameWriteBuffer frame = new FrameWriteBuffer(bytes);
    frame.beginFrame(FrameType.HEADERS, FrameFlags.END_HEADERS, 1);
    HpackEncoder.writeLiteralWithNameIndex(
        bytes, 2, "CONNECT".getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeIndexed(bytes, 6);
    HpackEncoder.writeLiteralWithNameIndex(
        bytes, 1, authority.getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteralWithNameIndex(
        bytes, 4, path.getBytes(StandardCharsets.US_ASCII), false);
    HpackEncoder.writeLiteral(
        bytes,
        ":protocol".getBytes(StandardCharsets.US_ASCII),
        "websocket".getBytes(StandardCharsets.US_ASCII));
    frame.endFrame();
    output.write(bytes.array(), 0, bytes.length());
  }

  private int awaitStatus() throws Exception {
    HpackDecoder decoder = new HpackDecoder();
    while (true) {
      WireFrame frame = readFrame();
      if (frame.type != FrameType.HEADERS.code() || frame.streamId != 1) {
        handle(frame);
        continue;
      }
      int[] status = {0};
      decoder.decode(
          frame.payload,
          0,
          frame.payload.length,
          (name, value, never) -> {
            if (name.length() == 7 && name.byteAt(0) == ':') {
              status[0] =
                  (value.byteAt(0) - '0') * 100
                      + (value.byteAt(1) - '0') * 10
                      + value.byteAt(2)
                      - '0';
            }
          });
      return status[0];
    }
  }

  private void sendWebSocketFrame(boolean fin, byte opcode, byte[] payload, boolean endStream)
      throws Exception {
    byte[] encoded = maskedFrame(fin, opcode, payload);
    int offset = 0;
    while (offset < encoded.length) {
      while (connectionWindow <= 0 || streamWindow <= 0) readAndHandleFrame();
      int count =
          Math.min(
              encoded.length - offset,
              Math.min(peerMaxFrame, Math.min(connectionWindow, streamWindow)));
      writeData(encoded, offset, count, endStream && offset + count == encoded.length);
      offset += count;
      connectionWindow -= count;
      streamWindow -= count;
    }
  }

  private void readAndHandleFrame() throws Exception {
    handle(readFrame());
  }

  private void handle(WireFrame frame) throws IOException {
    if (frame.type == FrameType.WINDOW_UPDATE.code()) {
      int increment = readInt(frame.payload, 0) & 0x7fff_ffff;
      if (frame.streamId == 0) connectionWindow += increment;
      else if (frame.streamId == 1) streamWindow += increment;
    } else if (frame.type == FrameType.DATA.code() && frame.streamId == 1) {
      responseData.write(frame.payload);
      responseEnded = (frame.flags & FrameFlags.END_STREAM) != 0;
    } else if (frame.type == FrameType.SETTINGS.code() && (frame.flags & FrameFlags.ACK) == 0) {
      writeEmpty(FrameType.SETTINGS, FrameFlags.ACK, 0);
    } else if (frame.type == FrameType.RST_STREAM.code() && frame.streamId == 1) {
      throw new IOException("WebSocket stream reset with " + readInt(frame.payload, 0));
    } else if (frame.type == FrameType.GOAWAY.code()) {
      throw new IOException("HTTP/2 connection closed with " + readInt(frame.payload, 4));
    }
  }

  private void writeData(byte[] payload, int offset, int length, boolean endStream)
      throws IOException {
    ByteWriter bytes = new ByteWriter(length + 9);
    FrameWriteBuffer frame = new FrameWriteBuffer(bytes);
    frame.beginFrame(FrameType.DATA, endStream ? FrameFlags.END_STREAM : 0, 1);
    bytes.writeBytes(payload, offset, length);
    frame.endFrame();
    output.write(bytes.array(), 0, bytes.length());
  }

  private void writeEmpty(FrameType type, int flags, int streamId) throws IOException {
    byte[] frame = {0, 0, 0, (byte) type.code(), (byte) flags, 0, 0, 0, (byte) streamId};
    output.write(frame);
  }

  private WireFrame readFrame() throws IOException {
    byte[] header = input.readNBytes(9);
    if (header.length != 9) throw new EOFException("HTTP/2 connection closed between frames");
    int length = ((header[0] & 0xff) << 16) | ((header[1] & 0xff) << 8) | (header[2] & 0xff);
    int streamId =
        ((header[5] & 0x7f) << 24)
            | ((header[6] & 0xff) << 16)
            | ((header[7] & 0xff) << 8)
            | (header[8] & 0xff);
    byte[] payload = input.readNBytes(length);
    if (payload.length != length) throw new EOFException("HTTP/2 frame truncated");
    return new WireFrame(header[3] & 0xff, header[4] & 0xff, streamId, payload);
  }

  private static byte[] maskedFrame(boolean fin, byte opcode, byte[] payload) {
    int lengthBytes = payload.length <= 125 ? 0 : payload.length <= 0xffff ? 2 : 8;
    byte[] frame = new byte[2 + lengthBytes + 4 + payload.length];
    int position = 0;
    frame[position++] = (byte) ((fin ? 0x80 : 0) | opcode);
    if (lengthBytes == 0) {
      frame[position++] = (byte) (0x80 | payload.length);
    } else if (lengthBytes == 2) {
      frame[position++] = (byte) (0x80 | 126);
      frame[position++] = (byte) (payload.length >>> 8);
      frame[position++] = (byte) payload.length;
    } else {
      frame[position++] = (byte) (0x80 | 127);
      long payloadLength = payload.length;
      for (int shift = 56; shift >= 0; shift -= 8) {
        frame[position++] = (byte) (payloadLength >>> shift);
      }
    }
    byte[] mask = {1, 2, 3, 4};
    System.arraycopy(mask, 0, frame, position, mask.length);
    position += mask.length;
    for (int i = 0; i < payload.length; i++) {
      frame[position + i] = (byte) (payload[i] ^ mask[i & 3]);
    }
    return frame;
  }

  private static int readInt(byte[] bytes, int offset) {
    return ((bytes[offset] & 0xff) << 24)
        | ((bytes[offset + 1] & 0xff) << 16)
        | ((bytes[offset + 2] & 0xff) << 8)
        | (bytes[offset + 3] & 0xff);
  }

  private record WireFrame(int type, int flags, int streamId, byte[] payload) {}
}
