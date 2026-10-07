package dev.relism.flash.http1;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.Duplex;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.transport.ConnectionContext;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static dev.relism.flash.http1.Http1ResponseWriter.*;

/**
 * {@link Duplex} on HTTP/1.1. A protocol switch hands the socket to its protocol: its
 * bytes go straight to the socket and are read in place from the connection's buffer. Anything
 * else is a chunked body that ends with the response's trailers, read alongside the request body.
 * One per connection, restarted for each exchange.
 */
final class Http1Duplex extends Duplex {

    private final ConnectionContext ctx;
    private final OutputStream out;
    private final byte[] bodyBuffer;
    private Request request;
    private InputStream body; // the request's, taken at the first read
    private int bodyPos;
    private int bodyLimit;
    private boolean head;
    private boolean keepAlive;
    private boolean switched;

    Http1Duplex(ConnectionContext ctx) {
        this.ctx = ctx;
        this.out = ctx.out();
        this.bodyBuffer = ctx.scratch().relayBuffer;
    }

    void start(Request request, boolean head, boolean keepAlive) {
        this.request = request;
        body = null;
        bodyPos = bodyLimit = 0;
        this.head = head;
        this.keepAlive = keepAlive;
        switched = false;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        commit();
        if (len == 0 || head) return;
        if (switched) {
            ctx.rawOut().write(b, off, len);
            return;
        }
        writeHex(out, len);
        out.write(CRLF);
        out.write(b, off, len);
        out.write(CRLF);
    }

    @Override
    public void flush() throws IOException {
        commit();
        if (!switched) out.flush();
    }

    @Override
    public int fill() throws IOException {
        if (switched) return ctx.in().fill();
        if (bodyPos < bodyLimit) return bodyLimit - bodyPos;
        // ponytail: one copy out of the request body's framing; a chunked in-place view if it matters
        if (body == null) body = request.body().stream();
        int n = body.read(bodyBuffer);
        if (n < 0) return -1;
        bodyPos = 0;
        bodyLimit = n;
        return n;
    }

    @Override
    public byte[] array() {
        return switched ? ctx.in().window() : bodyBuffer;
    }

    @Override
    public int offset() {
        return switched ? ctx.in().windowOffset() : bodyPos;
    }

    @Override
    public void advance(int n) {
        if (switched) ctx.in().advance(n);
        else bodyPos += n;
    }

    /** Ends a committed exchange; true when the connection now belongs to the protocol it switched to. */
    boolean finish() throws IOException {
        if (switched) return true;
        Response response = response();
        if (!head) {
            if (response.hasTrailers()) writeFinalChunk(out, response);
            else out.write(FINAL_CHUNK);
        }
        out.flush();
        return false;
    }

    private void commit() throws IOException {
        Response response = response();
        if (response.isCommitted()) return;
        ByteWriter h = head(response, ctx.configuration().isSendDate(), ctx.scratch());
        switched = switchesProtocol();
        if (!switched) {
            h.writeBytes(TRANSFER_CHUNKED);
            h.writeBytes(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
        }
        h.writeBytes(CRLF);
        out.write(h.array(), 0, h.length());
        response.commit();
        if (switched) {
            out.flush(); // the 101 leaves before the socket is the new protocol's
            ctx.in().clearDeadline(); // which paces its own reads
        }
    }
}
