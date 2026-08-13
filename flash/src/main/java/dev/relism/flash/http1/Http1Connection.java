package dev.relism.flash.http1;

import dev.relism.flash.RequestParser;
import dev.relism.flash.exceptions.MalformedRequestException;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.flash.transport.ConnectionContext;
import dev.relism.flash.transport.ConnectionProtocol;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketLoop;
import dev.relism.flash.websocket.WebSocketSession;
import dev.relism.flash.websocket.WebSocketUpgrade;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

/**
 * The HTTP/1.1 keep-alive request loop: parse → route → handle → respond, repeated until the
 * connection closes. Sole responsibility: drive that loop for one connection; parsing lives in
 * {@link RequestParser}, serialization in {@link Http1ResponseWriter}, and the WebSocket upgrade
 * path hands off to {@link WebSocketUpgrade}/{@link WebSocketLoop} entirely — once a connection
 * upgrades, this class has nothing further to do with it.
 */
public final class Http1Connection implements ConnectionProtocol {

    @Override
    public void run(ConnectionContext ctx) throws IOException {
        RequestParser parser = new RequestParser(
                ctx.configuration().getMaxHeaderBufferSize(),
                ctx.remoteAddress(),
                ctx.sslSocket());

        BufferedByteSource in  = ctx.in();
        OutputStream        out = ctx.out();
        byte[] idleProbe = new byte[1];

        // EX-06 (router half): created once per connection, exactly like `parser` above, and
        // reused across every request on this connection — see AbstractRouter#newScratch.
        Object routeScratch   = ctx.router().newScratch();
        Object wsRouteScratch = ctx.wsRouter().newScratch();

        // EX-21: one Response per connection, repositioned (never reallocated) per request.
        Response pooledResponse = new Response(200, ContentType.TEXT_PLAIN);

        while (!ctx.stopped().getAsBoolean()) {
            // EX-07: wait for the next request to begin, bounded by the generous
            // idle-keep-alive timeout — sitting idle between keep-alive requests is normal, not
            // an attack. Skipped when the parser already has bytes buffered from a previous
            // read (HTTP pipelining): the next request has, by definition, already started, so
            // waiting on the *source* for a fresh byte would wait for something that already
            // arrived and is sitting in the parser's own buffer.
            if (!parser.hasBufferedBytes()) {
                in.setDeadline(System.nanoTime() + ctx.configuration().getIdleKeepAliveTimeoutMs() * 1_000_000L);
                int firstByteSeen;
                try {
                    firstByteSeen = in.peek(idleProbe, 0, 1);
                } catch (SocketTimeoutException e) {
                    break; // idle timeout — nothing pending; close quietly, like EOF
                }
                if (firstByteSeen <= 0) break; // clean EOF
            }

            // Bytes have started arriving: tighten to the slowloris-specific bound for the rest
            // of the header block.
            in.setDeadline(System.nanoTime() + ctx.configuration().getHeaderReadTimeoutMs() * 1_000_000L);
            Request request;
            try {
                request = parser.parse(in);
            } catch (MalformedRequestException e) {
                // EX-02/03/08/18: a fixed, minimal, non-customizable rejection — never routed
                // through a handler or the user's exception handler — and the connection is
                // always closed afterwards, never kept alive.
                Response rejection = new Response(e.status(), e.getMessage(), ContentType.TEXT_PLAIN);
                Http1ResponseWriter.writeResponse(out, rejection, null, false, ctx.configuration().isSendDate(), ctx.scratch());
                break;
            } catch (SocketTimeoutException e) {
                break; // header-read deadline exceeded — close
            }
            if (request == null) break;

            if (request.method() == HttpMethod.GET && WebSocketUpgrade.isWebSocketUpgrade(request)) {
                in.clearDeadline(); // the WS session loop is long-lived; it paces itself
                WebSocketHandler wsHandler = ctx.wsRouter().route(request, wsRouteScratch);
                if (wsHandler == null) {
                    out.write(WebSocketUpgrade.REJECT_400);
                    out.flush();
                    break;
                }
                // Flush buffered HTTP bytes (the 101 response) before WebSocketSession takes
                // over rawOut — otherwise the handshake reply stays stuck in the buffered
                // stream and the client never sees it.
                WebSocketUpgrade.performHandshake(out, request, ctx.scratch());
                out.flush();
                request.drain();
                WebSocketSession session = new WebSocketSession(
                        in, ctx.rawOut(), ctx.configuration().getWsFrameBufferSize(), request, false);
                WebSocketLoop.run(session, wsHandler);
                return;
            }

            // Headers are fully read; the body (if any) may still be pending — whether the
            // handler consumes it or the automatic drain() below does, bound it by the same
            // deadline.
            in.setDeadline(System.nanoTime() + ctx.configuration().getBodyReadTimeoutMs() * 1_000_000L);

            boolean keepAlive = Http1KeepAlive.isKeepAlive(request);
            Response response = pooledResponse.reset(200, ContentType.TEXT_PLAIN);

            RequestHandler handler = ctx.router().route(request, routeScratch);
            if (handler == null) handler = ctx.router().getNotFoundHandler();

            try {
                Object result = handler.handle(request, response);
                if (result instanceof Response r) response = r;
                else if (result != null) response.setBody(result);
            } catch (Exception ex) {
                Object result = ctx.router().getExceptionHandler().handle(ex, request, response);
                if (result instanceof Response r) response = r;
                else if (result != null) response.setBody(result);
            }

            // EX-32: re-checked here, not just before dispatch — a shutdown that begins while
            // this handler was running (the common case: draining connections mid-request) must
            // still force this response to Connection: close, not whatever was decided before
            // the handler ran.
            boolean actuallyKeepAlive = keepAlive && !ctx.stopped().getAsBoolean();
            Http1ResponseWriter.writeResponse(out, response, request.method(), actuallyKeepAlive,
                    ctx.configuration().isSendDate(), ctx.scratch());
            request.drain();
            // EX-22/EX-21: these instances are about to be repositioned over the next request (or
            // dropped, if the connection closes) — poison them in dev mode so any reference the
            // handler improperly retained (a captured field, an async callback) fails loudly on
            // its next access instead of silently reading whatever comes next. Only the pooled
            // Response is recycled: if the handler returned a different instance, that object was
            // never pooled in the first place and owes nothing back to this connection.
            request.recycle();
            if (response == pooledResponse) pooledResponse.recycle();
            in.clearDeadline();
            if (!actuallyKeepAlive) break;
        }
    }
}
