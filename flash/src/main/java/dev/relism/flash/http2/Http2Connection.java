package dev.relism.flash.http2;

import dev.relism.flash.http2.Http2ConnectionScratch.ControlIntent;
import dev.relism.flash.http2.Http2ConnectionScratch.ControlKind;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameHeader;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameValidator;
import dev.relism.flash.http2.frame.Http2FrameReader;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.flash.transport.ConnectionContext;
import dev.relism.flash.transport.ConnectionProtocol;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.function.BooleanSupplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns one HTTP/2 connection's demultiplexing and connection-level protocol state. The demux loop
 * never invokes application code and never waits for a handler or body consumer; stream dispatch is
 * handed to independent virtual threads by the stream layer.
 */
@Slf4j
public final class Http2Connection implements ConnectionProtocol {
  private static final byte[] SHUTDOWN_PING = {
    (byte) 0x46, (byte) 0x4c, (byte) 0x41, (byte) 0x53,
    (byte) 0x48, (byte) 0x47, (byte) 0x4f, (byte) 0x21
  };

  private final Http2Settings peerSettings = new Http2Settings();
  private final Http2ConnectionScratch scratch = new Http2ConnectionScratch();
  private final Http2Settings.StreamWindowUpdater streamWindows;
  private final long settingsAckTimeoutMs;
  private final Http2HeaderBlockDecoder headerBlocks = new Http2HeaderBlockDecoder();

  private long connectionSendWindow = 65_535;
  private int outstandingLocalSettings;
  private long oldestSettingsSentNanos;
  private int lastProcessedStreamId;
  private int peerLastStreamId = Integer.MAX_VALUE;
  private int peerErrorCode;
  private boolean peerGoAway;
  private boolean gracefulStarted;
  private boolean gracefulFinished;

  public Http2Connection() {
    this(delta -> {}, Http2Limits.SETTINGS_ACK_TIMEOUT_MS);
  }

  public Http2Connection(Http2Settings.StreamWindowUpdater streamWindows) {
    this(streamWindows, Http2Limits.SETTINGS_ACK_TIMEOUT_MS);
  }

  Http2Connection(Http2Settings.StreamWindowUpdater streamWindows, long settingsAckTimeoutMs) {
    this.streamWindows = streamWindows;
    this.settingsAckTimeoutMs = settingsAckTimeoutMs;
  }

  @Override
  public void run(ConnectionContext ctx) throws IOException {
    Http2FrameWriter writer = new Http2FrameWriter(ctx.rawOut()::write);
    try {
      run(ctx.in(), writer, ctx.stopped());
    } finally {
      writer.close();
    }
  }

  void run(BufferedByteSource input, Http2FrameWriter writer, BooleanSupplier stopped)
      throws IOException {
    Http2FrameReader reader = new Http2FrameReader(input);
    runPrepared(input, reader, writer, stopped);
  }

  /** Runs with connection collaborators that were allocated during connection setup. */
  void runPrepared(
      BufferedByteSource input,
      Http2FrameReader reader,
      Http2FrameWriter writer,
      BooleanSupplier stopped)
      throws IOException {
    if (!verifyPreface(input)) return;

    sendConstant(writer, Http2Preface.serverSettings());
    sendConstant(writer, Http2Preface.initialConnectionWindow());
    outstandingLocalSettings = 1;
    oldestSettingsSentNanos = System.nanoTime();

    boolean firstFrame = true;
    try {
      while (!gracefulFinished && !peerGoAway) {
        if (stopped.getAsBoolean() && !gracefulStarted) startGracefulShutdown(writer);
        FrameHeader frame;
        try {
          frame = reader.readFrame(Math.min(100, nextReadTimeoutMs()));
        } catch (SocketTimeoutException timeout) {
          checkSettingsTimeout();
          if (stopped.getAsBoolean() && !gracefulStarted) {
            startGracefulShutdown(writer);
            continue;
          }
          if (reader.frameDeadlineExpired()) throw timeout;
          continue;
        }
        if (frame == null) break;
        try {
          FrameValidator.validate(frame, headerBlocks.insideHeaderBlock());
          if (headerBlocks.insideHeaderBlock() && frame.type() != FrameType.CONTINUATION) {
            throw Http2Exception.PROTOCOL_ERROR;
          }
          if (firstFrame && frame.type() != FrameType.SETTINGS) {
            throw Http2Exception.PROTOCOL_ERROR;
          }
          if (firstFrame && FrameFlags.isAck(frame.flags()) && frame.length() == 0) {
            throw Http2Exception.PROTOCOL_ERROR;
          }
          firstFrame = false;
          dispatch(frame, writer);
        } catch (Http2StreamException streamError) {
          sendRstStream(writer, streamError);
        } finally {
          reader.consumeFrame();
        }
        writer.drain();
        checkSettingsTimeout();
      }
    } catch (Http2Exception connectionError) {
      sendGoAway(
          writer, lastProcessedStreamId, connectionError.errorCode(), connectionError.getMessage());
    } catch (IOException io) {
      throw io;
    } catch (RuntimeException unexpected) {
      log.error("Unexpected failure in HTTP/2 demux loop", unexpected);
      sendGoAway(writer, lastProcessedStreamId, Http2ErrorCode.INTERNAL_ERROR, "internal error");
    }
  }

  private boolean verifyPreface(BufferedByteSource input) throws IOException {
    byte[] preface = scratch.prefaceBuffer();
    int read = 0;
    input.setDeadline(System.nanoTime() + Http2Limits.FRAME_READ_TIMEOUT_MS * 1_000_000L);
    try {
      while (read < preface.length) {
        int n = input.read(preface, read, preface.length - read);
        if (n < 0) return false;
        read += n;
      }
      return Http2Preface.matchesClientPreface(preface);
    } finally {
      input.clearDeadline();
    }
  }

  private void dispatch(FrameHeader frame, Http2FrameWriter writer) throws IOException {
    FrameType type = frame.type();
    if (type == null) return;
    switch (type) {
      case SETTINGS -> receiveSettings(frame, writer);
      case PING -> receivePing(frame, writer);
      case WINDOW_UPDATE -> receiveWindowUpdate(frame);
      case GOAWAY -> receiveGoAway(frame);
      case HEADERS, CONTINUATION -> {
        if (headerBlocks.accept(frame)) {
          lastProcessedStreamId = Math.max(lastProcessedStreamId, frame.streamId());
          if (!gracefulStarted) startGracefulShutdown(writer);
        }
      }
      default -> {
        // Stream semantics are introduced by the stream layer. Structurally-valid
        // frames are consumed here so connection-level state remains synchronized.
      }
    }
  }

  private void receiveSettings(FrameHeader frame, Http2FrameWriter writer) throws IOException {
    boolean ack = FrameFlags.isAck(frame.flags());
    if (ack) {
      if (frame.length() != 0) throw Http2Exception.FRAME_SIZE_ERROR;
      if (outstandingLocalSettings == 0) throw Http2Exception.PROTOCOL_ERROR;
      outstandingLocalSettings--;
      if (outstandingLocalSettings == 0) oldestSettingsSentNanos = 0;
      return;
    }
    peerSettings.apply(frame.buffer(), frame.payloadOffset(), frame.length(), streamWindows);
    sendConstant(writer, Http2Preface.settingsAck());
  }

  private void receivePing(FrameHeader frame, Http2FrameWriter writer) throws IOException {
    if (FrameFlags.isAck(frame.flags())) {
      if (gracefulStarted && matches(frame.buffer(), frame.payloadOffset(), SHUTDOWN_PING)) {
        sendGoAway(writer, lastProcessedStreamId, Http2ErrorCode.NO_ERROR, "shutdown complete");
        gracefulFinished = true;
      }
      return;
    }
    ControlIntent pong = scratch.acquire(ControlKind.PING);
    pong.frame(FrameType.PING, FrameFlags.ACK, 0, frame.buffer(), frame.payloadOffset(), 8);
    writer.writePriority(pong);
  }

  private void receiveWindowUpdate(FrameHeader frame) {
    int increment = readUInt31(frame.buffer(), frame.payloadOffset());
    if (increment == 0) throw Http2Exception.PROTOCOL_ERROR;
    if (frame.streamId() != 0) return;
    long next = connectionSendWindow + increment;
    if (next > Integer.MAX_VALUE) throw Http2Exception.FLOW_CONTROL_ERROR;
    connectionSendWindow = next;
  }

  private void receiveGoAway(FrameHeader frame) {
    peerLastStreamId = readUInt31(frame.buffer(), frame.payloadOffset());
    peerErrorCode = readInt(frame.buffer(), frame.payloadOffset() + 4);
    peerGoAway = true;
  }

  private void startGracefulShutdown(Http2FrameWriter writer) throws IOException {
    gracefulStarted = true;
    sendGoAway(writer, Integer.MAX_VALUE, Http2ErrorCode.NO_ERROR, "server shutting down");
    ControlIntent ping = scratch.acquire(ControlKind.PING);
    ping.frame(FrameType.PING, 0, 0, SHUTDOWN_PING, 0, SHUTDOWN_PING.length);
    writer.writePriority(ping);
  }

  private void sendRstStream(Http2FrameWriter writer, Http2StreamException error)
      throws IOException {
    ControlIntent rst = scratch.acquire(ControlKind.SETTINGS_OR_OTHER);
    rst.frame(FrameType.RST_STREAM, 0, error.streamId(), error.errorCode().bytes(), 0, 4);
    writer.writePriority(rst);
  }

  private void sendGoAway(
      Http2FrameWriter writer, int lastStreamId, Http2ErrorCode error, String debug)
      throws IOException {
    ControlIntent goAway = scratch.acquire(ControlKind.SETTINGS_OR_OTHER);
    goAway.goAway(lastStreamId, error, debug == null ? "" : debug);
    writer.writePriority(goAway);
  }

  private void sendConstant(Http2FrameWriter writer, byte[] bytes) throws IOException {
    ControlIntent intent = scratch.acquire(ControlKind.SETTINGS_OR_OTHER);
    intent.copy(bytes);
    writer.writePriority(intent);
  }

  private long nextReadTimeoutMs() {
    if (outstandingLocalSettings == 0) return Http2Limits.FRAME_READ_TIMEOUT_MS;
    long elapsed = System.nanoTime() - oldestSettingsSentNanos;
    long remainingNanos = settingsAckTimeoutMs * 1_000_000L - elapsed;
    if (remainingNanos <= 0) throw Http2Exception.SETTINGS_TIMEOUT;
    long remainingMs = Math.max(1, (remainingNanos + 999_999L) / 1_000_000L);
    return Math.min(Http2Limits.FRAME_READ_TIMEOUT_MS, remainingMs);
  }

  private void checkSettingsTimeout() {
    if (outstandingLocalSettings != 0
        && System.nanoTime() - oldestSettingsSentNanos >= settingsAckTimeoutMs * 1_000_000L) {
      throw Http2Exception.SETTINGS_TIMEOUT;
    }
  }

  private static boolean matches(byte[] buf, int off, byte[] expected) {
    int different = 0;
    for (int i = 0; i < expected.length; i++) different |= buf[off + i] ^ expected[i];
    return different == 0;
  }

  private static int readUInt31(byte[] buf, int off) {
    return readInt(buf, off) & 0x7FFF_FFFF;
  }

  private static int readInt(byte[] buf, int off) {
    return ((buf[off] & 0xFF) << 24)
        | ((buf[off + 1] & 0xFF) << 16)
        | ((buf[off + 2] & 0xFF) << 8)
        | (buf[off + 3] & 0xFF);
  }

  public Http2Settings peerSettings() {
    return peerSettings;
  }

  public long connectionSendWindow() {
    return connectionSendWindow;
  }

  public int peerLastStreamId() {
    return peerLastStreamId;
  }

  public int peerErrorCode() {
    return peerErrorCode;
  }

  void reset() {
    peerSettings.reset();
    connectionSendWindow = 65_535;
    outstandingLocalSettings = 0;
    oldestSettingsSentNanos = 0;
    lastProcessedStreamId = 0;
    peerLastStreamId = Integer.MAX_VALUE;
    peerErrorCode = 0;
    peerGoAway = false;
    gracefulStarted = false;
    gracefulFinished = false;
  }
}
