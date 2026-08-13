package dev.relism.flash.http2.stream;

import dev.relism.flash.bytes.PooledSlice;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http2.Http2ErrorCode;
import dev.relism.flash.http2.Http2StreamException;
import dev.relism.flash.http2.hpack.HpackHeaderBlock;
import dev.relism.flash.http2.message.Http2HeaderMap;
import dev.relism.flash.http2.message.Http2ResponseWriter;
import dev.relism.flash.http2.message.PseudoHeaders;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestBody;
import dev.relism.flash.models.RequestLine;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.AbstractRouter;
import java.net.InetSocketAddress;
import javax.net.ssl.SSLSocket;

/** Per-stream request, response, decoded-header and write state. */
public final class Http2Stream implements Http2ResponseWriter.Completion {
  private static final byte[] HTTP_2 = {'H', 'T', 'T', 'P', '/', '2'};

  private final HpackHeaderBlock headerBlock = new HpackHeaderBlock();
  private final PseudoHeaders pseudoHeaders = new PseudoHeaders();
  private final Http2HeaderMap headers = new Http2HeaderMap();
  private final RequestLine requestLine = new RequestLine();
  private final RequestBody requestBody = new RequestBody();
  private final Request request = new Request();
  private final Response response = new Response(200, ContentType.TEXT_PLAIN);
  private final Http2ResponseWriter responseWriter = new Http2ResponseWriter();
  private final PooledSlice path = new PooledSlice();
  private final PooledSlice query = new PooledSlice();
  private final PooledSlice protocol = new PooledSlice();

  private int id;
  private Http2StreamState state = Http2StreamState.IDLE;
  private int sendWindow = 65_535;
  private Http2StreamTable owner;
  private Object routeScratch;
  private volatile boolean dispatched;
  private volatile boolean cancelled;
  private boolean headersValidated;
  Http2Stream poolNext;

  Http2Stream() {
    responseWriter.completion(this);
    protocol.reset(HTTP_2, 0, HTTP_2.length);
  }

  void reset(int id, Http2StreamTable owner) {
    this.id = id;
    this.owner = owner;
    state = Http2StreamState.IDLE;
    sendWindow = 65_535;
    dispatched = false;
    cancelled = false;
    headersValidated = false;
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
    requestBody.reset(null, 0, null, 0, 0);
    return Request.forParsed(request, requestLine, requestBody, remoteAddress, sslSocket);
  }

  public void validateHeaders() {
    if (headersValidated) return;
    pseudoHeaders.validate(headerBlock, id);
    headersValidated = true;
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
  }

  public boolean cancelled() {
    return cancelled;
  }

  public int sendWindow() {
    return sendWindow;
  }

  public void adjustSendWindow(int delta) {
    long adjusted = (long) sendWindow + delta;
    if (adjusted > Integer.MAX_VALUE) throw new IllegalStateException("stream window overflow");
    sendWindow = (int) adjusted;
  }

  @Override
  public void responseWriteCompleted() {
    Http2StreamTable table = owner;
    if (table != null) table.release(this);
  }
}
