package dev.relism.flash.http2;

import dev.relism.flash.bytes.Pairs;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http2.Http2ConnectionScratch.ControlIntent;
import dev.relism.flash.http2.Http2ConnectionScratch.ControlKind;
import dev.relism.flash.http2.frame.FrameFlags;
import dev.relism.flash.http2.frame.FrameHeader;
import dev.relism.flash.http2.frame.FrameType;
import dev.relism.flash.http2.frame.FrameValidator;
import dev.relism.flash.http2.frame.Http2FrameReader;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.http2.frame.Padding;
import dev.relism.flash.http2.hpack.HeaderSink;
import dev.relism.flash.http2.message.DataBufferPool;
import dev.relism.flash.http2.stream.Http2FlowController;
import dev.relism.flash.http2.stream.Http2Stream;
import dev.relism.flash.http2.stream.Http2StreamState;
import dev.relism.flash.http2.stream.Http2StreamTable;
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
  private final DataBufferPool dataBuffers =
      new DataBufferPool(Http2Limits.MAX_FRAME_SIZE_LOCAL, Http2Limits.DATA_BUFFER_POOL_SIZE);
  private final Http2StreamTable streams =
      new Http2StreamTable(Http2Limits.MAX_CONCURRENT_STREAMS, dataBuffers);
  private static final HeaderSink DISCARD_HEADERS = (name, value, never) -> {};

  private Http2FlowController flowController;
  private int outstandingLocalSettings;
  private long oldestSettingsSentNanos;
  private int lastProcessedStreamId;
  private int peerLastStreamId = Integer.MAX_VALUE;
  private int peerErrorCode;
  private boolean peerGoAway;
  private boolean gracefulStarted;
  private boolean gracefulFinished;
  private int highestClientStreamId;
  private Http2Stream pendingHeaderStream;
  private boolean refusingHeaderStream;
  private boolean pendingTrailers;
  private Http2StreamDispatcher streamDispatcher;
  private final Http2Stream[] dispatchQueue = new Http2Stream[Http2Limits.MAX_CONCURRENT_STREAMS];
  private int dispatchCount;
  private final Http2AbuseGuard abuse = new Http2AbuseGuard();
  private long streamIdleTimeoutNanos = Http2Limits.STREAM_IDLE_TIMEOUT_MS * 1_000_000L;
  private long maxBodySize = Long.MAX_VALUE;
  private final Http2Stream[] idleSweep = new Http2Stream[Http2Limits.MAX_CONCURRENT_STREAMS];

  public Http2Connection() {
    this(delta -> {}, Http2Limits.SETTINGS_ACK_TIMEOUT_MS);
  }

  public Http2Connection(Http2Settings.StreamWindowUpdater streamWindows) {
    this(streamWindows, Http2Limits.SETTINGS_ACK_TIMEOUT_MS);
  }

  Http2Connection(Http2Settings.StreamWindowUpdater streamWindows, long settingsAckTimeoutMs) {
    this.streamWindows =
        delta -> {
          try {
            if (flowController == null) streams.adjustAllSendWindows(delta);
            else flowController.applyInitialWindowDelta(streams, delta);
          } catch (IllegalStateException overflow) {
            throw Http2Exception.FLOW_CONTROL_ERROR;
          }
          streamWindows.applyInitialWindowDelta(delta);
        };
    this.settingsAckTimeoutMs = settingsAckTimeoutMs;
  }

  @Override
  public void run(ConnectionContext ctx) throws IOException {
    configure(ctx.configuration());
    Http2FrameWriter writer = new Http2FrameWriter(ctx.rawOut()::write);
    flowController =
        new Http2FlowController(
            (streamId, increment) -> sendWindowUpdate(writer, streamId, increment));
    streamDispatcher =
        new Http2StreamDispatcher(
            ctx,
            writer,
            peerSettings,
            streams,
            flowController,
            (streamId, error) -> sendRstStream(writer, streamId, error));
    try {
      run(ctx.in(), writer, ctx.stopped());
    } finally {
      writer.close();
    }
  }

  void run(BufferedByteSource input, Http2FrameWriter writer, BooleanSupplier stopped)
      throws IOException {
    if (flowController == null) {
      flowController =
          new Http2FlowController(
              (streamId, increment) -> sendWindowUpdate(writer, streamId, increment));
    }
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
    PrefaceResult preface = verifyPreface(input);
    if (preface == PrefaceResult.TRUNCATED) return;
    if (preface == PrefaceResult.INVALID) {
      sendGoAway(writer, 0, Http2ErrorCode.PROTOCOL_ERROR, "invalid client preface");
      writer.drain();
      return;
    }
    abuse.start();

    sendConstant(writer, Http2Preface.serverSettings());
    sendConstant(writer, Http2Preface.initialConnectionWindow());
    outstandingLocalSettings = 1;
    oldestSettingsSentNanos = System.nanoTime();

    boolean firstFrame = true;
    try {
      while (!gracefulFinished && !peerGoAway) {
        abuse.checkBudgets();
        closeIdleStreams(writer);
        if (stopped.getAsBoolean() && !gracefulStarted) startGracefulShutdown(writer);
        FrameHeader frame;
        try {
          frame = reader.readFrame(Math.min(100, nextReadTimeoutMs()));
        } catch (SocketTimeoutException timeout) {
          checkSettingsTimeout();
          headerBlocks.checkTimeout();
          if (stopped.getAsBoolean() && !gracefulStarted) {
            startGracefulShutdown(writer);
            continue;
          }
          if (reader.frameDeadlineExpired()) throw timeout;
          continue;
        }
        if (frame == null) break;
        abuse.receivedFrame(frame.length());
        try {
          headerBlocks.checkTimeout();
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
          closeStreamAfterError(streamError.streamId());
        } finally {
          reader.consumeFrame();
        }
        writer.drain();
        if (dispatchCount > 0 && !reader.hasBufferedInput()) dispatchPendingStreams();
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

  private PrefaceResult verifyPreface(BufferedByteSource input) throws IOException {
    byte[] preface = scratch.prefaceBuffer();
    int read = 0;
    input.setDeadline(System.nanoTime() + Http2Limits.FRAME_READ_TIMEOUT_MS * 1_000_000L);
    try {
      while (read < preface.length) {
        int n = input.read(preface, read, preface.length - read);
        if (n < 0) return PrefaceResult.TRUNCATED;
        read += n;
      }
      return Http2Preface.matchesClientPreface(preface)
          ? PrefaceResult.MATCHED
          : PrefaceResult.INVALID;
    } finally {
      input.clearDeadline();
    }
  }

  private enum PrefaceResult {
    MATCHED,
    INVALID,
    TRUNCATED
  }

  private void dispatch(FrameHeader frame, Http2FrameWriter writer) throws IOException {
    FrameType type = frame.type();
    if (type == null) {
      abuse.uselessFrameReceived();
      return;
    }
    switch (type) {
      case SETTINGS -> receiveSettings(frame, writer);
      case PING -> receivePing(frame, writer);
      case WINDOW_UPDATE -> receiveWindowUpdate(frame);
      case GOAWAY -> receiveGoAway(frame);
      case HEADERS -> receiveHeaders(frame, writer);
      case CONTINUATION -> receiveContinuation(frame, writer);
      case DATA -> receiveData(frame);
      case RST_STREAM -> receiveRstStream(frame);
      case PRIORITY -> receivePriority(frame);
      default -> {}
    }
  }

  private void receiveHeaders(FrameHeader frame, Http2FrameWriter writer) throws IOException {
    int streamId = frame.streamId();
    if ((streamId & 1) == 0) throw Http2Exception.PROTOCOL_ERROR;
    Http2Stream existing = streams.get(streamId);
    if (existing != null) {
      existing.touch();
      if (existing.state() == Http2StreamState.HALF_CLOSED_REMOTE) {
        throw new Http2StreamException(
            streamId, Http2ErrorCode.STREAM_CLOSED, "stream is half-closed remotely");
      }
      if (!FrameFlags.isEndStream(frame.flags())) {
        throw new Http2StreamException(
            streamId, Http2ErrorCode.PROTOCOL_ERROR, "trailers require END_STREAM");
      }
      pendingHeaderStream = existing;
      pendingTrailers = true;
      existing.trailerBlock().reset();
      if (headerBlocks.accept(frame, existing.trailerBlock())) completeHeaders(writer, streamId);
      return;
    }
    if (streamId <= highestClientStreamId) {
      int closedKind = streams.closedKind(streamId);
      if (closedKind == Http2StreamTable.CLOSED_NORMALLY) {
        throw Http2Exception.of(Http2ErrorCode.STREAM_CLOSED, "frame on a closed stream");
      }
      if (closedKind == Http2StreamTable.CLOSED_BY_RESET) {
        throw new Http2StreamException(
            streamId, Http2ErrorCode.STREAM_CLOSED, "stream was reset");
      }
      throw Http2Exception.PROTOCOL_ERROR;
    }
    abuse.streamCreated();
    highestClientStreamId = streamId;
    pendingTrailers = false;

    pendingHeaderStream = streams.acquire(streamId);
    refusingHeaderStream = pendingHeaderStream == null;
    if (pendingHeaderStream != null) {
      flowController.initializeStreamSendWindow(
          pendingHeaderStream, peerSettings.initialWindowSize());
    }
    HeaderSink sink =
        refusingHeaderStream
            ? DISCARD_HEADERS
            : (pendingTrailers
                ? pendingHeaderStream.trailerBlock()
                : pendingHeaderStream.headerBlock());
    if (headerBlocks.accept(frame, sink)) completeHeaders(writer, frame.streamId());
  }

  private void receiveContinuation(FrameHeader frame, Http2FrameWriter writer) throws IOException {
    if (pendingHeaderStream == null && !refusingHeaderStream) {
      throw Http2Exception.PROTOCOL_ERROR;
    }
    HeaderSink sink =
        refusingHeaderStream
            ? DISCARD_HEADERS
            : (pendingTrailers
                ? pendingHeaderStream.trailerBlock()
                : pendingHeaderStream.headerBlock());
    if (headerBlocks.accept(frame, sink)) completeHeaders(writer, frame.streamId());
  }

  private void completeHeaders(Http2FrameWriter writer, int streamId) throws IOException {
    try {
      if (refusingHeaderStream) {
        sendRstStream(writer, streamId, Http2ErrorCode.REFUSED_STREAM);
      } else {
        Http2Stream stream = pendingHeaderStream;
        boolean dispatch;
        if (pendingTrailers) {
          stream.validateTrailers();
          stream.finishRequestBody();
          stream.transition(Http2StreamState.Event.RECV_HEADERS_ES);
          dispatch = !stream.dispatched();
        } else {
          if (streamDispatcher != null) stream.validateHeaders();
          dispatch = stream.prepareRequestBody(flowController, headerBlocks.endStream(), maxBodySize);
          stream.transition(
              headerBlocks.endStream()
                  ? Http2StreamState.Event.RECV_HEADERS_ES
                  : Http2StreamState.Event.RECV_HEADERS);
          lastProcessedStreamId = Math.max(lastProcessedStreamId, streamId);
        }
        if (streamDispatcher == null && !pendingTrailers) {
          streams.retire(stream, streamId);
          if (!gracefulStarted) startGracefulShutdown(writer);
        } else if (dispatch) {
          enqueueDispatch(stream);
        } else if (stream.responseStarted() && stream.responseWriter().finished()
            && !stream.responseInFlight() && stream.state() == Http2StreamState.CLOSED) {
          streams.retire(stream, streamId);
        }
      }
    } finally {
      pendingHeaderStream = null;
      refusingHeaderStream = false;
      pendingTrailers = false;
    }
  }

  private void receivePriority(FrameHeader frame) {
    abuse.uselessFrameReceived();
    int dependency = readUInt31(frame.buffer(), frame.payloadOffset());
    if (dependency == frame.streamId()) {
      throw new Http2StreamException(
          frame.streamId(), Http2ErrorCode.PROTOCOL_ERROR, "stream depends on itself");
    }
  }

  private void receiveData(FrameHeader frame) {
    if (frame.length() == 0) abuse.uselessFrameReceived();
    flowController.receiveConnectionBytes(frame.length());
    Http2Stream stream = streams.get(frame.streamId());
    if (stream == null) {
      discardConnectionBytes(frame.length());
      if (frame.streamId() > highestClientStreamId) throw Http2Exception.PROTOCOL_ERROR;
      throw new Http2StreamException(
          frame.streamId(), Http2ErrorCode.STREAM_CLOSED, "stream is closed");
    }
    boolean bodyAccepted = false;
    try {
      stream.touch();
      stream.transition(
          FrameFlags.isEndStream(frame.flags())
              ? Http2StreamState.Event.RECV_DATA_ES
              : Http2StreamState.Event.RECV_DATA);
      flowController.receiveStreamBytes(stream, frame.length());
      long unpadded =
          Padding.unpad(
              frame.buffer(),
              frame.payloadOffset(),
              frame.length(),
              FrameFlags.isPadded(frame.flags()));
      int dataOffset = Pairs.hi(unpadded);
      int dataLength = Pairs.lo(unpadded);
      if (frame.length() == 0) {
        if (stream.incrementEmptyDataFrames()
            > Http2Limits.MAX_EMPTY_DATA_FRAMES_PER_STREAM) {
          throw new Http2StreamException(
              frame.streamId(), Http2ErrorCode.ENHANCE_YOUR_CALM, "empty DATA frame limit exceeded");
        }
      } else {
        stream.resetEmptyDataFrames();
      }
      stream.receiveData(frame.buffer(), dataOffset, dataLength, frame.length());
      bodyAccepted = true;
      if (FrameFlags.isEndStream(frame.flags())) {
        stream.finishRequestBody();
        if (streamDispatcher != null && !stream.dispatched()) enqueueDispatch(stream);
        else if (stream.responseStarted() && stream.responseWriter().finished()
            && !stream.responseInFlight()
            && stream.state() == Http2StreamState.CLOSED) {
          streams.retire(stream, frame.streamId());
        }
      }
    } catch (RuntimeException failure) {
      if (!bodyAccepted) discardConnectionBytes(frame.length());
      throw failure;
    }
  }

  private void receiveRstStream(FrameHeader frame) {
    abuse.resetReceived();
    Http2Stream stream = streams.get(frame.streamId());
    if (stream == null) {
      if (frame.streamId() > highestClientStreamId) throw Http2Exception.PROTOCOL_ERROR;
      return;
    }
    boolean releaseDeferred =
        stream.dispatched() || stream.state() == Http2StreamState.HALF_CLOSED_REMOTE;
    stream.transition(Http2StreamState.Event.RECV_RST);
    if (!streams.removeIfSame(stream, frame.streamId())) return;
    streams.rememberReset(frame.streamId());
    if (releaseDeferred) {
      stream.cancel();
      if (stream.responseStarted() && !stream.responseInFlight()) streams.release(stream);
    } else {
      streams.release(stream);
    }
  }

  private void enqueueDispatch(Http2Stream stream) {
    if (dispatchCount == dispatchQueue.length) {
      throw new Http2StreamException(
          stream.id(), Http2ErrorCode.REFUSED_STREAM, "dispatch queue is full");
    }
    stream.markDispatched();
    dispatchQueue[dispatchCount++] = stream;
  }

  private void dispatchPendingStreams() {
    int count = dispatchCount;
    dispatchCount = 0;
    for (int i = 0; i < count; i++) {
      Http2Stream stream = dispatchQueue[i];
      dispatchQueue[i] = null;
      streamDispatcher.dispatch(stream);
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
    abuse.settingsReceived();
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
    abuse.pingReceived();
    ControlIntent pong = scratch.acquire(ControlKind.PING);
    pong.frame(FrameType.PING, FrameFlags.ACK, 0, frame.buffer(), frame.payloadOffset(), 8);
    writer.writePriority(pong);
  }

  private void receiveWindowUpdate(FrameHeader frame) {
    abuse.uselessFrameReceived();
    int increment = readUInt31(frame.buffer(), frame.payloadOffset());
    if (increment == 0) {
      if (frame.streamId() == 0) throw Http2Exception.PROTOCOL_ERROR;
      throw new Http2StreamException(
          frame.streamId(), Http2ErrorCode.PROTOCOL_ERROR, "zero window increment");
    }
    if (frame.streamId() != 0) {
      Http2Stream stream = streams.get(frame.streamId());
      if (stream == null) {
        if (frame.streamId() > highestClientStreamId) throw Http2Exception.PROTOCOL_ERROR;
        return;
      }
      try {
        stream.touch();
        flowController.increaseStreamSendWindow(stream, increment);
      } catch (IllegalStateException overflow) {
        throw new Http2StreamException(
            frame.streamId(), Http2ErrorCode.FLOW_CONTROL_ERROR, "stream window overflow");
      }
      if (streamDispatcher != null) streamDispatcher.streamWindowUpdated(stream);
      return;
    }
    flowController.increaseConnectionSendWindow(increment);
    if (streamDispatcher != null) streamDispatcher.connectionWindowUpdated();
  }

  private void receiveGoAway(FrameHeader frame) {
    peerLastStreamId = readUInt31(frame.buffer(), frame.payloadOffset());
    peerErrorCode = readInt(frame.buffer(), frame.payloadOffset() + 4);
    peerGoAway = true;
  }

  void configure(ServerConfig configuration) {
    abuse.configure(configuration);
    maxBodySize = configuration.getMaxBodySize();
    long idle = configuration.getH2StreamIdleTimeoutMs();
    if (idle <= 0) throw new IllegalArgumentException("h2StreamIdleTimeoutMs must be positive");
    streamIdleTimeoutNanos = idle > Long.MAX_VALUE / 1_000_000L
        ? Long.MAX_VALUE : idle * 1_000_000L;
  }

  private void closeIdleStreams(Http2FrameWriter writer) throws IOException {
    int count = streams.copyValues(idleSweep);
    long now = System.nanoTime();
    for (int i = 0; i < count; i++) {
      Http2Stream stream = idleSweep[i];
      idleSweep[i] = null;
      if (!stream.idleExpired(now, streamIdleTimeoutNanos)) continue;
      int streamId = stream.id();
      if (!streams.removeIfSame(stream, streamId)) continue;
      streams.rememberReset(streamId);
      sendRstStream(writer, streamId, Http2ErrorCode.CANCEL);
      if (stream.dispatched()) stream.cancel();
      else streams.release(stream);
    }
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

  private void sendRstStream(Http2FrameWriter writer, int streamId, Http2ErrorCode error)
      throws IOException {
    ControlIntent rst = scratch.acquire(ControlKind.SETTINGS_OR_OTHER);
    rst.frame(FrameType.RST_STREAM, 0, streamId, error.bytes(), 0, 4);
    writer.writePriority(rst);
  }

  private void sendWindowUpdate(Http2FrameWriter writer, int streamId, int increment)
      throws IOException {
    ControlIntent update = scratch.acquire(ControlKind.SETTINGS_OR_OTHER);
    update.windowUpdate(streamId, increment);
    writer.writePriority(update);
  }

  private void discardConnectionBytes(int bytes) {
    try {
      flowController.discarded(bytes);
    } catch (IOException failure) {
      throw new IllegalStateException("failed to restore connection flow-control window", failure);
    }
  }

  private void closeStreamAfterError(int streamId) {
    Http2Stream stream = streams.get(streamId);
    if (stream == null) return;
    if (!streams.removeIfSame(stream, streamId)) return;
    streams.rememberReset(streamId);
    if (stream.dispatched()) stream.cancel();
    else streams.release(stream);
    if (pendingHeaderStream == stream) pendingHeaderStream = null;
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
    return flowController == null ? 65_535 : flowController.connectionSendWindow();
  }

  public int peerLastStreamId() {
    return peerLastStreamId;
  }

  public int peerErrorCode() {
    return peerErrorCode;
  }

  void reset() {
    peerSettings.reset();
    flowController = null;
    outstandingLocalSettings = 0;
    oldestSettingsSentNanos = 0;
    lastProcessedStreamId = 0;
    peerLastStreamId = Integer.MAX_VALUE;
    peerErrorCode = 0;
    peerGoAway = false;
    gracefulStarted = false;
    gracefulFinished = false;
    highestClientStreamId = 0;
    pendingHeaderStream = null;
    refusingHeaderStream = false;
    dispatchCount = 0;
  }
}
