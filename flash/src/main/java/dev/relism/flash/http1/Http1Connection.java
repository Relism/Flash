package dev.relism.flash.http1;

import dev.relism.flash.RequestParser;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Exchange;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.MalformedRequestException;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.Router;
import dev.relism.flash.transport.BufferedByteSource;
import dev.relism.flash.transport.ConnectionContext;
import dev.relism.flash.transport.ConnectionProtocol;

import java.io.IOException;
import java.io.OutputStream;
import java.net.SocketTimeoutException;

/**
 * The HTTP/1.1 keep-alive loop: parse, dispatch through the {@link Router}, respond, until the
 * connection closes. Parsing lives in {@link RequestParser}, serialization in
 * {@link Http1ResponseWriter}; a response written as it goes is a
 * {@link Http1Duplex}, and one that switched protocols keeps the connection for good.
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
        Http1Duplex duplex = new Http1Duplex(ctx);
        Exchange exchange = new Exchange();

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

            duplex.start(request, request.method() == HttpMethod.HEAD, keepAlive);
            Response answer;
            try {
                answer = ctx.router().dispatch(exchange.reset(request, response, duplex), routeScratch);
            } catch (IOException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IOException("response failed after it started", failure);
            }
            boolean streamed = response.isCommitted();
            if (streamed && duplex.finish()) return;
            if (!streamed) response = answer;

            // A server that started stopping while the handler ran closes this connection.
            boolean actuallyKeepAlive = keepAlive && !ctx.stopped().getAsBoolean();
            if (!streamed) {
                Http1ResponseWriter.writeResponse(out, response, request.method(), actuallyKeepAlive,
                        config.isSendDate(), ctx.scratch());
            }
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
