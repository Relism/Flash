package dev.relism.flash.http2.stream;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2Limits;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;
import dev.relism.flash.http2.message.DataBufferPool;
import dev.relism.flash.http2.message.Http2HeaderMap;
import dev.relism.flash.http2.message.Http2RequestBody;
import dev.relism.flash.http2.message.Http2ResponseWriter;
import dev.relism.flash.http2.message.PseudoHeaders;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestBody;
import dev.relism.flash.models.RequestLine;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.fpr.core.ByteView;
import java.io.IOException;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSocket;

/** Per-stream request, response, decoded-header and write state. */
public final class Http2Stream
    implements Http2ResponseWriter.Completion, Http2RequestBody.ConsumptionListener, Runnable {
  public interface ResponseSink {
    void handleRequest(Http2Stream stream);

    void responseBatchCompleted(Http2Stream stream);

    void resumeResponse(Http2Stream stream);
  }

  private static final byte[] HTTP_2 = {'H', 'T', 'T', 'P', '/', '2'};

  private final HpackHeaderBlock headerBlock = new HpackHeaderBlock();
  private final PseudoHeaders pseudoHeaders = new PseudoHeaders();
  private final Http2HeaderMap headers = new Http2HeaderMap();
  private final RequestLine requestLine = new RequestLine();
  private final RequestBody requestBody = new RequestBody();
  private final Http2RequestBody http2Body;
  private final Request request = new Request();
  private final Response response = new Response(200, ContentType.TEXT_PLAIN);
  private final Http2ResponseWriter responseWriter = new Http2ResponseWriter();
  private final PooledSlice path = new PooledSlice();
  private final PooledSlice query = new PooledSlice();
  private final PooledSlice protocol = new PooledSlice();
  private final PooledSlice scanName = new PooledSlice();
  private final PooledSlice scanValue = new PooledSlice();

  private int id;
  private Http2StreamState state = Http2StreamState.IDLE;
  private int sendWindow = 65_535;
  private int receiveWindow = Http2Limits.INITIAL_WINDOW_SIZE_LOCAL;
  private int consumedReceiveBytes;
  private int emptyDataFrames;
  private Http2StreamTable owner;
  private Object routeScratch;
  private volatile boolean dispatched;
  private volatile boolean cancelled;
  private boolean headersValidated;
  private Http2FlowController flowController;
  private ResponseSink responseSink;
  private volatile boolean responseInFlight;
  private volatile boolean responseStarted;
  private boolean releaseClaimed;
  private volatile boolean resumeTask;
  Http2Stream poolNext;

  Http2Stream(DataBufferPool dataBuffers) {
    http2Body = new Http2RequestBody(dataBuffers);
    responseWriter.completion(this);
    protocol.reset(HTTP_2, 0, HTTP_2.length);
  }

  void reset(int id, Http2StreamTable owner) {
    this.id = id;
    this.owner = owner;
    state = Http2StreamState.IDLE;
    sendWindow = 65_535;
    receiveWindow = Http2Limits.INITIAL_WINDOW_SIZE_LOCAL;
    consumedReceiveBytes = 0;
    emptyDataFrames = 0;
    dispatched = false;
    cancelled = false;
    headersValidated = false;
    responseInFlight = false;
    responseStarted = false;
    releaseClaimed = false;
    resumeTask = false;
    responseSink = null;
    headerBlock.reset();
  }

  void clear() {
    request.recycle();
    response.recycle();
    id = 0;
    owner = null;
    state = Http2StreamState.CLOSED;
  }

  public Request assembleRequest(InetSocketAddress remoteAddress, SSLSocket sslSocket) {
    validateHeaders();
    headers.reset(headerBlock, pseudoHeaders);
    PooledSlice rawPath = pseudoHeaders.path();
    if (rawPath.array() == null) rawPath = pseudoHeaders.authority();
    int question = -1;
    for (int i = 0; i < rawPath.length(); i++) {
      if (rawPath.byteAt(i) == '?') {
        question = i;
        break;
      }
    }
    if (question < 0) {
      path.reset(rawPath.array(), rawPath.offset(), rawPath.length());
      query.reset(null, 0, 0);
    } else {
      path.reset(rawPath.array(), rawPath.offset(), question);
      query.reset(
          rawPath.array(), rawPath.offset() + question + 1, rawPath.length() - question - 1);
    }
    PooledSlice methodBytes = pseudoHeaders.method();
    HttpMethod method =
        HttpMethod.fromBytes(methodBytes.array(), methodBytes.offset(), methodBytes.length());
    if (method == null) {
      throw new Http2StreamException(
          id, Http2ErrorCode.PROTOCOL_ERROR, "unsupported request method");
    }
    requestLine.reset(method, path, question < 0 ? null : query, protocol, headers);
    requestBody.reset(http2Body, http2Body.declaredLength(), null, 0, 0);
    return Request.forParsed(request, requestLine, requestBody, remoteAddress, sslSocket);
  }

  public void validateHeaders() {
    if (headersValidated) return;
    pseudoHeaders.validate(headerBlock, id);
    headersValidated = true;
  }

  public boolean prepareRequestBody(Http2FlowController flowController, boolean endStream) {
    this.flowController = flowController;
    long contentLength = parseContentLength();
    if (contentLength < 0 && endStream) contentLength = 0;
    boolean inline = contentLength >= 0 && contentLength <= Http2Limits.INLINE_BODY_THRESHOLD;
    http2Body.begin(contentLength, inline, this);
    if (endStream) http2Body.finish(id);
    return endStream || !inline;
  }

  public void receiveData(byte[] source, int offset, int length, int flowControlledBytes) {
    http2Body.offer(id, source, offset, length, flowControlledBytes);
  }

  public void finishRequestBody() {
    http2Body.finish(id);
  }

  private long parseContentLength() {
    long parsed = -1;
    for (int i = 0; i < headerBlock.count(); i++) {
      headerBlock.get(i, scanName, scanValue);
      if (!equals(scanName, "content-length")) continue;
      long value = parseDecimal(scanValue);
      if (parsed >= 0 && parsed != value) {
        throw new Http2StreamException(
            id, Http2ErrorCode.PROTOCOL_ERROR, "conflicting content-length fields");
      }
      parsed = value;
    }
    return parsed;
  }

  private long parseDecimal(ByteView value) {
    if (value.length() == 0) {
      throw new Http2StreamException(id, Http2ErrorCode.PROTOCOL_ERROR, "empty content-length");
    }
    long parsed = 0;
    for (int i = 0; i < value.length(); i++) {
      int digit = (value.byteAt(i) & 0xff) - '0';
      if (digit < 0 || digit > 9 || parsed > (Http2Limits.MAX_REQUEST_BODY_SIZE - digit) / 10L) {
        throw new Http2StreamException(
            id, Http2ErrorCode.PROTOCOL_ERROR, "invalid or oversized content-length");
      }
      parsed = parsed * 10 + digit;
    }
    return parsed;
  }

  private static boolean equals(ByteView value, String expected) {
    if (value.length() != expected.length()) return false;
    for (int i = 0; i < value.length(); i++) {
      if ((value.byteAt(i) & 0xff) != expected.charAt(i)) return false;
    }
    return true;
  }

  public Response resetResponse() {
    return response.reset(200, ContentType.TEXT_PLAIN);
  }

  public void transition(Http2StreamState.Event event) {
    state = state.transition(id, event);
  }

  public int id() {
    return id;
  }

  public Http2StreamState state() {
    return state;
  }

  public HpackHeaderBlock headerBlock() {
    return headerBlock;
  }

  public Http2ResponseWriter responseWriter() {
    return responseWriter;
  }

  public void responseSink(ResponseSink responseSink) {
    this.responseSink = responseSink;
  }

  public void markResponseStarted() {
    responseStarted = true;
  }

  public boolean responseStarted() {
    return responseStarted;
  }

  public void markResumeTask() {
    resumeTask = true;
  }

  public synchronized boolean beginResponseBatch() {
    if (responseInFlight) return false;
    responseInFlight = true;
    return true;
  }

  public synchronized void endResponseBatch() {
    responseInFlight = false;
  }

  public synchronized boolean responseInFlight() {
    return responseInFlight;
  }

  synchronized boolean claimRelease() {
    if (releaseClaimed) return false;
    releaseClaimed = true;
    return true;
  }

  public Object routeScratch(AbstractRouter router) {
    if (routeScratch == null) routeScratch = router.newScratch();
    return routeScratch;
  }

  public void markDispatched() {
    dispatched = true;
  }

  public boolean dispatched() {
    return dispatched;
  }

  public void cancel() {
    cancelled = true;
    int discarded = http2Body.cancel();
    if (discarded != 0 && flowController != null) {
      try {
        flowController.discarded(discarded);
      } catch (IOException failure) {
        throw new IllegalStateException("failed to restore discarded flow-control bytes", failure);
      }
    }
  }

  public boolean cancelled() {
    return cancelled;
  }

  public synchronized int sendWindow() {
    return sendWindow;
  }

  public synchronized void adjustSendWindow(int delta) {
    long adjusted = (long) sendWindow + delta;
    if (adjusted > Integer.MAX_VALUE) throw new IllegalStateException("stream window overflow");
    sendWindow = (int) adjusted;
  }

  public synchronized boolean receiveBytes(int bytes) {
    if (bytes > receiveWindow) return false;
    receiveWindow -= bytes;
    return true;
  }

  public synchronized int consumedReceiveBytes(int bytes) {
    consumedReceiveBytes += bytes;
    if (consumedReceiveBytes < Http2Limits.INITIAL_WINDOW_SIZE_LOCAL / 2) {
      return 0;
    }
    int increment = consumedReceiveBytes;
    receiveWindow += increment;
    consumedReceiveBytes = 0;
    return increment;
  }

  public int incrementEmptyDataFrames() {
    return ++emptyDataFrames;
  }

  public void resetEmptyDataFrames() {
    emptyDataFrames = 0;
  }

  @Override
  public void consumed(int flowControlledBytes) throws IOException {
    flowController.consumed(this, flowControlledBytes);
  }

  @Override
  public void responseWriteCompleted() {
    ResponseSink sink = responseSink;
    if (sink != null) sink.responseBatchCompleted(this);
  }

  @Override
  public void run() {
    ResponseSink sink = responseSink;
    if (sink == null) return;
    if (resumeTask) sink.resumeResponse(this);
    else sink.handleRequest(this);
  }
}
