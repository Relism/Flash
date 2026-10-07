package dev.relism.flash.http2.stream;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.Exchange;
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
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestBody;
import dev.relism.flash.http.RequestLine;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.Router;
import dev.relism.fpr.core.ByteView;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Set;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
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
  private final HpackHeaderBlock trailerBlock = new HpackHeaderBlock();
  private final PseudoHeaders pseudoHeaders = new PseudoHeaders();
  private final Http2HeaderMap headers = new Http2HeaderMap();
  private final Http2HeaderMap trailers = new Http2HeaderMap();
  private final RequestLine requestLine = new RequestLine();
  private final RequestBody requestBody = new RequestBody();
  private final Http2RequestBody http2Body;
  private final Request request = new Request();
  private final Response response = new Response(200, ContentType.TEXT_PLAIN);
  private final Exchange exchange = new Exchange();
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
  private Router.Scratch routeScratch;
  private volatile boolean dispatched;
  private volatile boolean cancelled;
  private boolean headersValidated;
  private Http2FlowController flowController;
  private ResponseSink responseSink;
  private WriteState writeState = WriteState.IDLE;
  private volatile boolean responseStarted;
  private boolean releaseClaimed;
  private volatile boolean resumeTask;
  private volatile long lastActivityNanos;
  private Duplex duplex;
  private final ReentrantLock writeLock = new ReentrantLock();
  private final Condition writeChanged = writeLock.newCondition();
  Http2Stream poolNext;

  private enum WriteState {
    IDLE,
    BATCH,
    DUPLEX,
    DUPLEX_BATCH;

    boolean batch() {
      return this == BATCH || this == DUPLEX_BATCH;
    }

    boolean duplex() {
      return this == DUPLEX || this == DUPLEX_BATCH;
    }
  }

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
    writeState = WriteState.IDLE;
    responseStarted = false;
    releaseClaimed = false;
    resumeTask = false;
    responseSink = null;
    headerBlock.reset();
    trailerBlock.reset();
    trailers.reset(trailerBlock);
    touch();
  }

  void clear() {
    id = 0;
    owner = null;
    state = Http2StreamState.CLOSED;
  }

  public Request assembleRequest(InetSocketAddress remoteAddress, SSLSocket sslSocket, Set<String> trustedProxies) {
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
    if (pseudoHeaders.websocket()) method = HttpMethod.GET;
    requestLine.reset(method, path, question < 0 ? null : query, protocol, headers);
    requestBody.reset(http2Body, http2Body.declaredLength(), null, 0, 0);
    Request assembled =
        Request.forParsed(request, requestLine, requestBody, remoteAddress, sslSocket, trustedProxies);
    assembled.setTrailers(trailers);
    if (pseudoHeaders.websocket()) assembled.connectProtocol("websocket");
    return assembled;
  }

  public void validateHeaders() {
    if (headersValidated) return;
    pseudoHeaders.validate(headerBlock, id);
    headersValidated = true;
  }

  public boolean prepareRequestBody(Http2FlowController flowController, boolean endStream, long maxBodySize) {
    this.flowController = flowController;
    long contentLength = parseContentLength(maxBodySize);
    if (contentLength < 0 && endStream) contentLength = 0;
    boolean inline = contentLength >= 0 && contentLength <= Http2Limits.INLINE_BODY_THRESHOLD;
    http2Body.begin(contentLength, inline, this, maxBodySize);
    if (endStream) http2Body.finish(id);
    return endStream || !inline;
  }

  public void receiveData(byte[] source, int offset, int length, int flowControlledBytes) {
    http2Body.offer(id, source, offset, length, flowControlledBytes);
  }

  public void finishRequestBody() {
    http2Body.finish(id);
  }

  public void validateTrailers() {
    PseudoHeaders.validateTrailers(trailerBlock, id);
    trailers.reset(trailerBlock);
  }

  private long parseContentLength(long max) {
    long parsed = -1;
    for (int i = 0; i < headerBlock.count(); i++) {
      headerBlock.get(i, scanName, scanValue);
      if (!equals(scanName, "content-length")) continue;
      long value = parseDecimal(scanValue, max);
      if (parsed >= 0 && parsed != value) {
        throw new Http2StreamException(
            id, Http2ErrorCode.PROTOCOL_ERROR, "conflicting content-length fields");
      }
      parsed = value;
    }
    return parsed;
  }

  private long parseDecimal(ByteView value, long max) {
    if (value.length() == 0) {
      throw new Http2StreamException(id, Http2ErrorCode.PROTOCOL_ERROR, "empty content-length");
    }
    long parsed = 0;
    for (int i = 0; i < value.length(); i++) {
      int digit = (value.byteAt(i) & 0xff) - '0';
      if (digit < 0 || digit > 9 || parsed > (max - digit) / 10L) {
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

  public HpackHeaderBlock trailerBlock() {
    return trailerBlock;
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

  /** The duplex bound to this stream's pooled response, or null before the first one is made. */
  public Duplex duplex() {
    return duplex;
  }

  public void duplex(Duplex duplex) {
    this.duplex = duplex;
  }

  public Exchange exchange() {
    return exchange;
  }

  /** The request body, read in place by a duplex response. */
  public Http2RequestBody body() {
    return http2Body;
  }

  /** A duplex response becomes this stream's writer until {@link #leaveDuplex}. */
  public void enterDuplex() {
    writeLock.lock();
    try {
      if (writeState != WriteState.IDLE) throw new IllegalStateException("stream already has a writer");
      writeState = WriteState.DUPLEX;
    } finally {
      writeLock.unlock();
    }
  }

  /** Hands release back to the batch path; true when no batch is in flight and the caller releases. */
  public boolean leaveDuplex() {
    writeLock.lock();
    try {
      boolean release;
      if (writeState == WriteState.DUPLEX) {
        writeState = WriteState.IDLE;
        release = true;
      } else if (writeState == WriteState.DUPLEX_BATCH) {
        writeState = WriteState.BATCH;
        release = false;
      } else {
        throw new IllegalStateException("stream has no duplex writer");
      }
      writeChanged.signalAll();
      return release;
    } finally {
      writeLock.unlock();
    }
  }

  /** Signals a duplex writer after a window update or reset. */
  public boolean signalDuplex() {
    writeLock.lock();
    try {
      if (!writeState.duplex()) return false;
      writeChanged.signalAll();
      return true;
    } finally {
      writeLock.unlock();
    }
  }

  /** Completes a duplex batch; false lets the ordinary response scheduler handle it. */
  public boolean completeDuplexBatch() {
    writeLock.lock();
    try {
      if (writeState != WriteState.DUPLEX_BATCH) return false;
      writeState = WriteState.DUPLEX;
      writeChanged.signalAll();
      return true;
    } finally {
      writeLock.unlock();
    }
  }

  public boolean beginResponseBatch() {
    writeLock.lock();
    try {
      if (writeState == WriteState.IDLE) writeState = WriteState.BATCH;
      else if (writeState == WriteState.DUPLEX) writeState = WriteState.DUPLEX_BATCH;
      else return false;
      return true;
    } finally {
      writeLock.unlock();
    }
  }

  public void endResponseBatch() {
    writeLock.lock();
    try {
      if (writeState != WriteState.BATCH) {
        throw new IllegalStateException("stream has no ordinary response batch");
      }
      writeState = WriteState.IDLE;
      writeChanged.signalAll();
    } finally {
      writeLock.unlock();
    }
  }

  public boolean responseInFlight() {
    writeLock.lock();
    try {
      return writeState.batch();
    } finally {
      writeLock.unlock();
    }
  }

  /** Waits until the frame writer has consumed the current batch. */
  public void awaitResponseBatch() throws IOException {
    writeLock.lock();
    try {
      while (writeState == WriteState.DUPLEX_BATCH && !cancelled) awaitWriteChange();
      if (cancelled) throw new IOException("stream reset");
    } finally {
      writeLock.unlock();
    }
  }

  /** Atomically rechecks both flow-control windows before sleeping. */
  public int awaitSendWindow(Http2FlowController controller, int requested) throws IOException {
    writeLock.lock();
    try {
      for (;;) {
        if (cancelled) throw new IOException("stream reset");
        int granted = controller.reserveSend(this, requested);
        if (granted != 0) return granted;
        awaitWriteChange();
      }
    } finally {
      writeLock.unlock();
    }
  }

  private void awaitWriteChange() throws IOException {
    try {
      writeChanged.await();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted while writing HTTP/2 stream", interrupted);
    }
  }

  synchronized boolean claimRelease() {
    if (releaseClaimed) return false;
    releaseClaimed = true;
    return true;
  }

  public Router.Scratch routeScratch(Router router) {
    if (routeScratch == null) routeScratch = router.newScratch();
    return routeScratch;
  }

  public boolean websocketConnect() {
    return pseudoHeaders.websocket();
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
    // Symmetric with http2Body.cancel() above, for the outbound leg: closes a still-open
    // streaming response body (e.g. a reverse proxy's pooled backend connection) so an abandoned
    // stream — RST_STREAM from the peer, connection teardown — doesn't leak it. No-op if the
    // response never streamed a body or already finished normally.
    responseWriter.abort();
    signalDuplex();
  }

  public boolean cancelled() {
    return cancelled;
  }

  public void touch() {
    lastActivityNanos = System.nanoTime();
  }

  public boolean idleExpired(long nowNanos, long timeoutNanos) {
    return timeoutNanos > 0 && nowNanos - lastActivityNanos >= timeoutNanos;
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
