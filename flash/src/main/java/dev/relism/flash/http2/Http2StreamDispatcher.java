package dev.relism.flash.http2;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.http2.message.Http2ResponseWriter;
import dev.relism.flash.http2.stream.Http2FlowController;
import dev.relism.flash.http2.stream.Http2Stream;
import dev.relism.flash.http2.stream.Http2StreamState;
import dev.relism.flash.http2.stream.Http2StreamTable;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.ResponseStreamOutputStream;
import dev.relism.flash.transport.ConnectionContext;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketLoop;
import dev.relism.flash.websocket.WebSocketSession;
import java.io.IOException;
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
      Request request = stream.assembleRequest(context.remoteAddress(), context.sslSocket());
      Response pooled = stream.resetResponse();
      Response response = pooled;
      if (!Http2Authority.isServed(request.header("host"), request.sslSession())) {
        response.status(HttpStatus.MISDIRECTED_REQUEST);
        if (stream.websocketConnect()) response.type(ContentType.NONE).streaming(output -> {});
      } else if (stream.websocketConnect()) {
        WebSocketHandler handler =
            context.wsRouter().route(request, stream.wsRouteScratch(context.wsRouter()));
        response.type(ContentType.NONE);
        if (handler == null) {
          response.status(HttpStatus.NOT_FOUND).streaming(output -> {});
        } else {
          response.streaming(
              output ->
                  WebSocketLoop.run(
                      new WebSocketSession(
                          request.body().stream(),
                          new ResponseStreamOutputStream(output),
                          context.configuration().getWsFrameBufferSize(),
                          request,
                          false),
                      handler));
        }
      } else {
        Object routeScratch = stream.routeScratch(context.router());
        RequestHandler handler = context.router().route(request, routeScratch);
        if (handler == null) handler = context.router().getNotFoundHandler();
        try {
          Object result = handler.handle(request, response);
          if (result instanceof Response returned) response = returned;
          else if (result != null) response.setBody(result);
        } catch (Exception handlerFailure) {
          Object result =
              context.router().getExceptionHandler().handle(handlerFailure, request, response);
          if (result instanceof Response returned) response = returned;
          else if (result != null) response.setBody(result);
        }
      }

      boolean pushStreaming = response.isPushStreaming();
      if (!pushStreaming) request.drain();
      Http2ResponseWriter responseWriter = stream.responseWriter();
      if (stream.cancelled()) {
        request.recycle();
        if (response == pooled) pooled.recycle();
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
                  true,
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
      if (!pushStreaming) request.recycle();
      stream.markResponseStarted();
      applyBatchTransition(stream, responseWriter);
      if (!stream.beginResponseBatch()) {
        throw new IllegalStateException("response batch already in flight");
      }
      frameWriter.write(responseWriter);
    } catch (Exception failure) {
      failAndRelease(stream, Http2ErrorCode.INTERNAL_ERROR, failure);
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
        streams.retire(stream, streamId);
      }
      return;
    }
    scheduleResume(stream);
  }

  private void failAndRelease(Http2Stream stream, Http2ErrorCode error, Exception cause) {
    int streamId = stream.id();
    if (!streams.removeIfSame(stream, streamId)) return;
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
}
