package dev.relism.flash.http2;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http2.frame.Http2FrameWriter;
import dev.relism.flash.http2.message.Http2ResponseWriter;
import dev.relism.flash.http2.stream.Http2Stream;
import dev.relism.flash.http2.stream.Http2StreamState;
import dev.relism.flash.http2.stream.Http2StreamTable;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.transport.ConnectionContext;
import java.io.IOException;
import java.util.concurrent.RejectedExecutionException;
import lombok.extern.slf4j.Slf4j;

/** Dispatches completed request streams without blocking the connection demultiplexer. */
@Slf4j
final class Http2StreamDispatcher {
  @FunctionalInterface
  interface FailureSink {
    void fail(int streamId, Http2ErrorCode errorCode) throws IOException;
  }

  private final ConnectionContext context;
  private final Http2FrameWriter frameWriter;
  private final Http2Settings peerSettings;
  private final Http2StreamTable streams;
  private final FailureSink failures;
  private volatile boolean firstResponse = true;

  Http2StreamDispatcher(
      ConnectionContext context,
      Http2FrameWriter frameWriter,
      Http2Settings peerSettings,
      Http2StreamTable streams,
      FailureSink failures) {
    this.context = context;
    this.frameWriter = frameWriter;
    this.peerSettings = peerSettings;
    this.streams = streams;
    this.failures = failures;
  }

  void dispatch(Http2Stream stream) {
    if (stream.cancelled()) {
      streams.release(stream);
      return;
    }
    stream.markDispatched();
    try {
      context.executor().execute(() -> handle(stream));
    } catch (RejectedExecutionException rejected) {
      failAndRelease(stream, Http2ErrorCode.REFUSED_STREAM, rejected);
    }
  }

  private void handle(Http2Stream stream) {
    try {
      Request request = stream.assembleRequest(context.remoteAddress(), context.sslSocket());
      Response pooled = stream.resetResponse();
      Response response = pooled;
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

      Http2ResponseWriter responseWriter = stream.responseWriter();
      if (stream.cancelled()) {
        request.recycle();
        if (response == pooled) pooled.recycle();
        streams.release(stream);
        return;
      }
      boolean prepared;
      synchronized (this) {
        boolean tableUpdate = firstResponse;
        prepared =
            responseWriter.prepare(
                response,
                stream.id(),
                request.method() == HttpMethod.HEAD,
                context.configuration().isSendDate(),
                true,
                context.configuration().isH2HuffmanDynamicValues(),
                tableUpdate,
                peerSettings.maxFrameSize(),
                peerSettings.maxHeaderListSize(),
                (int) Math.min(stream.sendWindow(), Integer.MAX_VALUE));
        if (prepared) {
          firstResponse = false;
          stream.transition(Http2StreamState.Event.SEND_HEADERS_ES);
          request.recycle();
          if (response == pooled) pooled.recycle();
          streams.remove(stream.id());
          frameWriter.write(responseWriter);
        }
      }
      if (!prepared) {
        request.recycle();
        if (response == pooled) pooled.recycle();
        failAndRelease(stream, Http2ErrorCode.INTERNAL_ERROR, null);
      }
    } catch (Exception failure) {
      failAndRelease(stream, Http2ErrorCode.INTERNAL_ERROR, failure);
    }
  }

  private void failAndRelease(Http2Stream stream, Http2ErrorCode error, Exception cause) {
    if (stream.id() == 0) return;
    if (cause != null) log.error("HTTP/2 stream {} failed", stream.id(), cause);
    streams.remove(stream.id());
    try {
      failures.fail(stream.id(), error);
    } catch (IOException writeFailure) {
      log.debug("Failed to write RST_STREAM for {}", stream.id(), writeFailure);
    } finally {
      streams.release(stream);
    }
  }
}
