package dev.relism.flash.http2.stream;

import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2Exception;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.http2.Http2StreamException;
import java.io.IOException;

/** Connection-level half of HTTP/2's two-level flow-control accounting. */
public final class Http2FlowController {
  @FunctionalInterface
  public interface WindowUpdateSink {
    void update(int streamId, int increment) throws IOException;
  }

  private final WindowUpdateSink updates;
  private int receiveWindow = Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL;
  private int consumedSinceUpdate;
  private long sendWindow = 65_535;

  public Http2FlowController(WindowUpdateSink updates) {
    this.updates = updates;
  }

  public synchronized void receiveConnectionBytes(int bytes) {
    if (bytes < 0) throw new IllegalArgumentException("bytes must not be negative");
    if (bytes > receiveWindow) throw Http2Exception.FLOW_CONTROL_ERROR;
    receiveWindow -= bytes;
  }

  public void consumed(Http2Stream stream, int bytes) throws IOException {
    int connectionIncrement = 0;
    synchronized (this) {
      consumedSinceUpdate += bytes;
      if (consumedSinceUpdate >= Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL / 2) {
        connectionIncrement = consumedSinceUpdate;
        receiveWindow += connectionIncrement;
        consumedSinceUpdate = 0;
      }
    }
    int streamIncrement = stream.consumedReceiveBytes(bytes);
    if (streamIncrement != 0) updates.update(stream.id(), streamIncrement);
    if (connectionIncrement != 0) updates.update(0, connectionIncrement);
  }

  public void discarded(int bytes) throws IOException {
    int increment = 0;
    synchronized (this) {
      consumedSinceUpdate += bytes;
      if (consumedSinceUpdate >= Http2Limits.CONNECTION_WINDOW_SIZE_LOCAL / 2) {
        increment = consumedSinceUpdate;
        receiveWindow += increment;
        consumedSinceUpdate = 0;
      }
    }
    if (increment != 0) updates.update(0, increment);
  }

  public synchronized int reserveSend(Http2Stream stream, int requested) {
    int streamWindow = stream.sendWindow();
    if (requested <= 0 || sendWindow <= 0 || streamWindow <= 0) return 0;
    int granted =
        (int)
            Math.min(requested, Math.min(sendWindow, Math.min(streamWindow, Integer.MAX_VALUE)));
    sendWindow -= granted;
    stream.adjustSendWindow(-granted);
    return granted;
  }

  public synchronized void refundSend(Http2Stream stream, int bytes) {
    if (bytes == 0) return;
    sendWindow += bytes;
    stream.adjustSendWindow(bytes);
  }

  public synchronized void increaseConnectionSendWindow(int increment) {
    long next = sendWindow + increment;
    if (next > Integer.MAX_VALUE) throw Http2Exception.FLOW_CONTROL_ERROR;
    sendWindow = next;
  }

  public synchronized void increaseStreamSendWindow(Http2Stream stream, int increment) {
    stream.adjustSendWindow(increment);
  }

  public synchronized void initializeStreamSendWindow(Http2Stream stream, int initialWindow) {
    stream.adjustSendWindow(initialWindow - 65_535);
  }

  public synchronized void applyInitialWindowDelta(Http2StreamTable streams, int delta) {
    streams.adjustAllSendWindows(delta);
  }

  public void receiveStreamBytes(Http2Stream stream, int bytes) {
    if (!stream.receiveBytes(bytes)) {
      throw new Http2StreamException(
          stream.id(), Http2ErrorCode.FLOW_CONTROL_ERROR, "stream receive window exceeded");
    }
  }

  public synchronized int connectionReceiveWindow() {
    return receiveWindow;
  }

  public synchronized long connectionSendWindow() {
    return sendWindow;
  }
}
