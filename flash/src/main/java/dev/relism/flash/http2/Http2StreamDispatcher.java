package dev.relism.flash.http2;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.http2.message.Http2ResponseWriter;
import dev.relism.flash.http2.stream.Http2FlowController;
import dev.relism.flash.http2.stream.Http2Stream;
import dev.relism.flash.http2.stream.Http2StreamState;
import dev.relism.flash.http2.stream.Http2StreamTable;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.transport.ConnectionContext;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;

/** Dispatches completed request streams without blocking the connection demultiplexer. */
@Slf4j
final class Http2StreamDispatcher implements Http2Stream.ResponseSink {
  @FunctionalInterface
  interface FailureSink {
    void fail(int streamId, Http2ErrorCode errorCode) throws IOException;
  }

  private final ConnectionContext context;
  private final Http2FrameWriter frameWriter;
  private final Http2Settings peerSettings;
  private final Http2StreamTable streams;
  private final Http2FlowController flowController;
  private final FailureSink failures;
  private final Http2Stream[] resumeScratch =
      new Http2Stream[Http2Limits.MAX_CONCURRENT_STREAMS];
  private volatile boolean firstResponse = true;
  /** The configured proxies when this connection comes from one of them, else null. */
  private final Set<String> trustedProxies;

  Http2StreamDispatcher(
      ConnectionContext context,
      Http2FrameWriter frameWriter,
      Http2Settings peerSettings,
      Http2StreamTable streams,
      Http2FlowController flowController,
      FailureSink failures) {
    this.context = context;
    this.frameWriter = frameWriter;
    this.peerSettings = peerSettings;
    this.streams = streams;
    this.flowController = flowController;
    this.failures = failures;
    InetSocketAddress peer = context.remoteAddress();
    Set<String> trusted = context.configuration().getTrustedProxies();
    this.trustedProxies = peer != null && peer.getAddress() != null
        && trusted.contains(peer.getAddress().getHostAddress()) ? trusted : null;
  }

  void streamWindowUpdated(Http2Stream stream) {
    scheduleResume(stream);
  }

  void connectionWindowUpdated() {
    int count = streams.copyValues(resumeScratch);
    for (int i = 0; i < count; i++) {
      Http2Stream stream = resumeScratch[i];
      resumeScratch[i] = null;
      scheduleResume(stream);
    }
  }

  private void scheduleResume(Http2Stream stream) {
    if (stream.signalDuplex()) return;
    if (!stream.responseStarted() || stream.cancelled()) return;
    if (!stream.beginResponseBatch()) return;
    stream.markResumeTask();
    try {
      context.executor().execute(stream);
    } catch (RejectedExecutionException rejected) {
      stream.endResponseBatch();
      failAndRelease(stream, Http2ErrorCode.REFUSED_STREAM, rejected);
    }
  }

  void dispatch(Http2Stream stream) {
    if (stream.cancelled()) {
      streams.release(stream);
      return;
    }
    stream.markDispatched();
    stream.responseSink(this);
    try {
      context.executor().execute(stream);
    } catch (RejectedExecutionException rejected) {
      failAndRelease(stream, Http2ErrorCode.REFUSED_STREAM, rejected);
    }
  }

  @Override
  public void handleRequest(Http2Stream stream) {
    handle(stream);
  }

  private void handle(Http2Stream stream) {
    stream.touch();
    if (stream.cancelled()) {
      streams.release(stream);
      return;
    }
    try {
      Request request = stream.assembleRequest(context.remoteAddress(), context.sslSocket(), trustedProxies);
      Response pooled = stream.resetResponse();
      if (stream.duplex() == null) stream.duplex(new StreamDuplex(stream));
      ((StreamDuplex) stream.duplex()).head = request.method() == HttpMethod.HEAD;
      Response response = pooled;
      if (!Http2Authority.isServed(request.header("host"), request.sslSession())) {
        response.status(HttpStatus.MISDIRECTED_REQUEST);
      } else {
        Response answer = context.router().dispatch(
            stream.exchange().reset(request, response, stream.duplex()),
            stream.routeScratch(context.router()));
        if (pooled.isCommitted()) {
          ((StreamDuplex) stream.duplex()).finish();
          return;
        }
        response = answer;
      }
      // RFC 8441: a refused extended CONNECT is answered by its status alone.
      boolean statusAlone = stream.websocketConnect();
      if (statusAlone) response.type(ContentType.NONE).body((byte[]) null);

      request.drain();
      Http2ResponseWriter responseWriter = stream.responseWriter();
      boolean dev = context.configuration().isDev();
      if (stream.cancelled()) {
        if (dev) {
          request.recycle();
          if (response == pooled) pooled.recycle();
        }
        streams.release(stream);
        return;
      }
      boolean headRequest = request.method() == HttpMethod.HEAD;
      int reserved;
      int used;
      synchronized (this) {
        boolean tableUpdate = firstResponse;
        reserved = flowController.reserveSend(stream, peerSettings.maxFrameSize());
        used = 0;
        try {
          used =
              responseWriter.startFlowControlled(
                  response,
                  stream.id(),
                  headRequest,
                  context.configuration().isSendDate(),
                  !statusAlone,
                  context.configuration().isH2HuffmanDynamicValues(),
                  tableUpdate,
                  peerSettings.maxFrameSize(),
                  peerSettings.maxHeaderListSize(),
                  reserved);
        } finally {
          flowController.refundSend(stream, reserved - used);
        }
        firstResponse = false;
      }
      if (dev) request.recycle();
      stream.markResponseStarted();
      applyBatchTransition(stream, responseWriter);
      if (!stream.beginResponseBatch()) {
        throw new IllegalStateException("response batch already in flight");
      }
      detachFinalBatch(stream, responseWriter);
      frameWriter.write(responseWriter);
    } catch (Exception failure) {
      if (stream.duplex() instanceof StreamDuplex duplex && duplex.owner) duplex.abort(failure);
      else failAndRelease(stream, Http2ErrorCode.INTERNAL_ERROR, failure);
    }
  }

  private void tryResumeResponse(Http2Stream stream) {
    stream.touch();
    if (stream.cancelled()) {
      stream.endResponseBatch();
      streams.release(stream);
      return;
    }
    Http2ResponseWriter responseWriter = stream.responseWriter();
    int streamId = stream.id();
    if (responseWriter.finished()) {
      stream.endResponseBatch();
      if (stream.state() == Http2StreamState.CLOSED) {
        streams.retire(stream, streamId);
      }
      return;
    }
    int reserved = flowController.reserveSend(stream, peerSettings.maxFrameSize());
    if (reserved == 0) {
      stream.endResponseBatch();
      return;
    }
    try {
      int used = 0;
      try {
        used = responseWriter.resume(peerSettings.maxFrameSize(), reserved);
      } finally {
        flowController.refundSend(stream, reserved - used);
      }
      applyBatchTransition(stream, responseWriter);
      detachFinalBatch(stream, responseWriter);
      frameWriter.write(responseWriter);
    } catch (Exception failure) {
      stream.endResponseBatch();
      failAndRelease(stream, Http2ErrorCode.INTERNAL_ERROR, failure);
    }
  }

  @Override
  public void resumeResponse(Http2Stream stream) {
    tryResumeResponse(stream);
  }

  private static void applyBatchTransition(
      Http2Stream stream, Http2ResponseWriter responseWriter) {
    if (responseWriter.headersInBatch()) {
      if (responseWriter.finished() && responseWriter.dataBytesInBatch() == 0
          && !responseWriter.trailerHeadersInBatch()) {
        stream.transition(Http2StreamState.Event.SEND_HEADERS_ES);
        return;
      }
      stream.transition(Http2StreamState.Event.SEND_HEADERS);
    }
    if (responseWriter.dataBytesInBatch() != 0 || responseWriter.endStreamInBatch()) {
      if (responseWriter.dataBytesInBatch() != 0) {
        stream.transition(
            responseWriter.endStreamInBatch() && !responseWriter.trailerHeadersInBatch()
                ? Http2StreamState.Event.SEND_DATA_ES
                : Http2StreamState.Event.SEND_DATA);
      }
      if (responseWriter.trailerHeadersInBatch()) {
        stream.transition(Http2StreamState.Event.SEND_HEADERS_ES);
      } else if (responseWriter.dataBytesInBatch() == 0 && responseWriter.endStreamInBatch()) {
        stream.transition(Http2StreamState.Event.SEND_DATA_ES);
      }
    }
  }

  @Override
  public void responseBatchCompleted(Http2Stream stream) {
    stream.touch();
    if (stream.completeDuplexBatch()) return;
    stream.endResponseBatch();
    int streamId = stream.id();
    if (streamId == 0) return;
    if (stream.cancelled()) {
      streams.remove(stream.id());
      streams.release(stream);
      return;
    }
    if (stream.responseWriter().finished()) {
      if (stream.state() == Http2StreamState.CLOSED) {
        if (!streams.retire(stream, streamId)) streams.release(stream);
      }
      return;
    }
    scheduleResume(stream);
  }

  private void detachFinalBatch(Http2Stream stream, Http2ResponseWriter writer) {
    if (writer.finished() && stream.state() == Http2StreamState.CLOSED) {
      streams.detach(stream, stream.id());
    }
  }

  private void failAndRelease(Http2Stream stream, Http2ErrorCode error, Exception cause) {
    int streamId = stream.id();
    if (!streams.removeIfSame(stream, streamId) && stream.id() != streamId) return;
    if (cause != null) log.error("HTTP/2 stream {} failed", streamId, cause);
    try {
      stream.cancel();
    } catch (RuntimeException cancellationFailure) {
      log.debug("Failed to cancel HTTP/2 stream {} cleanly", streamId, cancellationFailure);
    }
    try {
      failures.fail(streamId, error);
    } catch (IOException writeFailure) {
      log.debug("Failed to write RST_STREAM for {}", streamId, writeFailure);
    } finally {
      streams.release(stream);
    }
  }

  /**
   * {@link Duplex} on one stream. The writing thread builds each batch, hands it to the frame
   * writer and waits for it to leave, so a write blocks while the socket or the peer's window
   * cannot take more. Reads are the request body's DATA buffers, in place. One per pooled stream,
   * bound to its response.
   */
  private final class StreamDuplex extends Duplex {
    private final Http2Stream stream;
    private boolean head;
    private boolean owner; // between the commit and the last batch; see Http2Stream#enterDuplex
    private int unsent; // window reserved for bytes the frame writer has not taken yet

    StreamDuplex(Http2Stream stream) {
      this.stream = stream;
    }

    @Override
    public void write(byte[] bytes, int offset, int length) throws IOException {
      commit();
      if (head) return;
      Http2ResponseWriter writer = stream.responseWriter();
      int frame = peerSettings.maxFrameSize();
      while (length > 0) {
        int granted = flowController.reserveSend(stream, length);
        if (granted == 0) {
          send();
          granted = stream.awaitSendWindow(flowController, length);
        }
        writer.duplexData(bytes, offset, granted, frame);
        unsent += granted;
        offset += granted;
        length -= granted;
        if (writer.dataBytesInBatch() >= frame) send();
      }
    }

    @Override
    public void flush() throws IOException {
      commit();
      send();
    }

    /** Ends a committed response: its trailers or END_STREAM, sent without waiting. */
    void finish() throws IOException {
      if (stream.cancelled()) throw new IOException("stream reset");
      Http2ResponseWriter writer = stream.responseWriter();
      writer.duplexEnd(peerSettings.maxFrameSize());
      applyBatchTransition(stream, writer);
      stream.beginResponseBatch();
      stream.markResponseStarted();
      owner = false;
      stream.leaveDuplex();
      detachFinalBatch(stream, writer);
      unsent = 0;
      frameWriter.write(writer);
    }

    /** Breaks the exchange off after its response started: a reset, unless the peer reset it first. */
    void abort(Exception failure) {
      owner = false;
      if (unsent != 0) flowController.refundSend(stream, unsent);
      unsent = 0;
      int streamId = stream.id();
      if (!stream.cancelled()) {
        log.error("HTTP/2 stream {} failed", streamId, failure);
        streams.removeIfSame(stream, streamId);
        stream.cancel();
        try {
          failures.fail(streamId, Http2ErrorCode.INTERNAL_ERROR);
        } catch (IOException writeFailure) {
          log.debug("Failed to write RST_STREAM for {}", streamId, writeFailure);
        }
      }
      if (stream.leaveDuplex()) streams.release(stream);
    }

    private void commit() throws IOException {
      Response response = response();
      if (response.isCommitted()) return;
      if (stream.cancelled()) throw new IOException("stream reset");
      stream.enterDuplex();
      owner = true;
      synchronized (Http2StreamDispatcher.this) {
        stream.responseWriter().duplexHeaders(
            response,
            stream.id(),
            context.configuration().isSendDate(),
            context.configuration().isH2HuffmanDynamicValues(),
            firstResponse,
            peerSettings.maxFrameSize(),
            peerSettings.maxHeaderListSize());
        firstResponse = false;
      }
      response.commit();
    }

    /** Hands the batch to the frame writer and waits until it has left; false when there was none. */
    private boolean send() throws IOException {
      Http2ResponseWriter writer = stream.responseWriter();
      if (!writer.duplexPending()) return false;
      if (stream.cancelled()) throw new IOException("stream reset");
      applyBatchTransition(stream, writer);
      stream.beginResponseBatch();
      unsent = 0;
      stream.touch();
      frameWriter.write(writer);
      stream.awaitResponseBatch();
      writer.duplexSent();
      return true;
    }

    @Override
    public int fill() throws IOException {
      return stream.body().fill();
    }

    @Override
    public byte[] array() {
      return stream.body().window();
    }

    @Override
    public int offset() {
      return stream.body().windowOffset();
    }

    @Override
    public void advance(int n) {
      stream.body().advance(n);
    }
  }
}
