package dev.relism.flash.http1;

import dev.relism.flash.RequestParser;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.MalformedRequestException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.Router;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.flash.transport.ConnectionContext;
import dev.relism.flash.transport.ConnectionProtocol;
import dev.relism.flash.websocket.Upgrade;
import dev.relism.flash.websocket.WebSocketLoop;
import dev.relism.flash.websocket.WebSocketSession;
import dev.relism.flash.websocket.WebSocketUpgrade;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

/**
 * The HTTP/1.1 keep-alive loop: parse, dispatch through the {@link Router}, respond, until the
 * connection closes. Parsing lives in {@link RequestParser}, serialization in
 * {@link Http1ResponseWriter}; a request its WebSocket route accepted hands the connection to
 * {@link WebSocketLoop} for good.
 */
public final class Http1Connection implements ConnectionProtocol {

    @Override
    public void run(ConnectionContext ctx) throws IOException {
        ServerConfig config = ctx.configuration();
        RequestParser parser = new RequestParser(config, ctx.remoteAddress(), ctx.sslSocket());
        BufferedByteSource in = ctx.in();
        OutputStream out = ctx.out();
        byte[] idleProbe = new byte[1];
        Router.Scratch routeScratch = ctx.router().newScratch();
        Response pooledResponse = new Response(200, ContentType.TEXT_PLAIN);

        while (!ctx.stopped().getAsBoolean()) {
            // Idle between keep-alive requests is normal, so the looser bound applies until a byte
            // arrives. Skipped when the parser already holds the next pipelined request.
            if (!parser.hasBufferedBytes()) {
                in.setDeadline(System.nanoTime() + config.getIdleKeepAliveTimeoutMs() * 1_000_000L);
                int firstByteSeen;
                try {
                    firstByteSeen = in.peek(idleProbe, 0, 1);
                } catch (SocketTimeoutException e) {
                    break;
                }
                if (firstByteSeen <= 0) break;
            }

            // Bytes are arriving: the slowloris bound applies to the rest of the header block.
            in.setDeadline(System.nanoTime() + config.getHeaderReadTimeoutMs() * 1_000_000L);
            Request request;
            try {
                request = parser.parse(in);
            } catch (MalformedRequestException e) {
                // A malformed request never reaches a handler, and the connection is closed after it.
                Response rejection = new Response(e.status(), e.getMessage(), ContentType.TEXT_PLAIN);
                Http1ResponseWriter.writeResponse(out, rejection, null, false, config.isSendDate(), ctx.scratch());
                break;
            } catch (SocketTimeoutException e) {
                break;
            }
            if (request == null) break;

            // The body, whether the handler reads it or the drain below does, is bounded too.
            in.setDeadline(System.nanoTime() + config.getBodyReadTimeoutMs() * 1_000_000L);
            boolean keepAlive = Http1KeepAlive.isKeepAlive(request);
            Response response = pooledResponse.reset(200, ContentType.TEXT_PLAIN);

            Object answer = ctx.router().dispatch(request, response,
                    request.method() == HttpMethod.GET && WebSocketUpgrade.isWebSocketUpgrade(request), routeScratch);
            if (answer instanceof Upgrade upgrade) {
                in.clearDeadline(); // the socket loop paces itself
                WebSocketUpgrade.performHandshake(out, request, ctx.scratch());
                out.flush(); // the 101 must leave before WebSocketSession takes over rawOut
                request.drain();
                WebSocketLoop.run(new WebSocketSession(in, ctx.rawOut(), config.getWsFrameBufferSize(), request, false),
                        upgrade.handler());
                return;
            }
            response = (Response) answer;

            // A server that started stopping while the handler ran closes this connection.
            boolean actuallyKeepAlive = keepAlive && !ctx.stopped().getAsBoolean();
            Http1ResponseWriter.writeResponse(out, response, request.method(), actuallyKeepAlive,
                    config.isSendDate(), ctx.scratch());
            request.drain();
            if (config.isDev()) {
                // Poisoned in development, so a request or response a handler kept past its return
                // fails loudly on its next use instead of reading the next request's data.
                request.recycle();
                if (response == pooledResponse) pooledResponse.recycle();
            }
            in.clearDeadline();
            if (!actuallyKeepAlive) break;
        }
    }
}
