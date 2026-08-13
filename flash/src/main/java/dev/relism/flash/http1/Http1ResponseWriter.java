package dev.relism.flash.http1;

import dev.relism.flash.http.DateHeader;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.Response;
import dev.relism.flash.transport.ConnectionScratch;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Serializes a {@link Response} as an HTTP/1.1 message. Sole responsibility: response
 * serialization — routing, handler dispatch, and the request loop live in
 * {@link Http1Connection}.
 *
 * <p>Zero-allocation: the decimal encoding of the status code / {@code Content-Length} and the
 * relay buffer used for streaming bodies both come from the connection's {@link ConnectionScratch}
 * ({@code EX-06}) instead of a per-call allocation or a {@code ThreadLocal}.
 */
public final class Http1ResponseWriter {

    private Http1ResponseWriter() {
    }

    private static final byte[] HTTP_1_1              = "HTTP/1.1 ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CRLF                  = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE          = "Content-Type: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_LENGTH        = "Content-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_CLOSE      = "Connection: close\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_KEEPALIVE  = "Connection: keep-alive\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_CHUNKED      = "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINAL_CHUNK           = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNKNOWN_STATUS_SUFFIX = " Unknown".getBytes(StandardCharsets.UTF_8);

    /**
     * Writes {@code response} to {@code out} as a complete HTTP/1.1 message.
     *
     * @param method   the request method — {@code null} is treated as "not HEAD" (used for
     *                  parser-rejection responses, which never reach a handler and so have no
     *                  associated method)
     * @param sendDate whether to include the {@code Date} header ({@code FlashConfiguration#isSendDate()})
     */
    public static void writeResponse(OutputStream out, Response response, HttpMethod method,
                                      boolean keepAlive, boolean sendDate, ConnectionScratch scratch) throws IOException {
        int statusCode = response.getStatusCode();
        // RFC 9110 §8.6/§15: 204, 304 and all 1xx responses MUST NOT carry Content-Length or a
        // body at all — not "an empty one", none (EX-15). A HEAD response (RFC 9110 §9.3.2)
        // still reports the Content-Length GET would have, but never writes body bytes.
        boolean noContentAllowed = statusCode == 204 || statusCode == 304 || (statusCode >= 100 && statusCode < 200);
        boolean suppressBody     = noContentAllowed || method == HttpMethod.HEAD;

        out.write(HTTP_1_1);
        byte[] statusBytes = response.getStatusBytes();
        if (statusBytes != null) out.write(statusBytes);
        else writeStatusPhrase(out, statusCode, scratch);
        out.write(CRLF);

        // EX-15: a Content-Type of ContentType.NONE (empty byte[]) used to still emit the line
        // "Content-Type: \r\n" — a header with no value. Skip the line entirely instead.
        byte[] contentType = response.getContentType();
        if (contentType != null && contentType.length > 0) {
            out.write(CONTENT_TYPE);
            out.write(contentType);
            out.write(CRLF);
        }

        // EX-16: precomputed once per second by a shared daemon thread — one volatile read,
        // one write(byte[]), never a per-response format call.
        if (sendDate) out.write(DateHeader.bytes());

        response.writeHeaders(out);

        if (response.isStreaming()) {
            writeStreamingBody(out, response, keepAlive, noContentAllowed, suppressBody, scratch);
        } else {
            byte[] body = response.getBody();
            int len = body != null ? body.length : 0;
            if (!noContentAllowed) {
                out.write(CONTENT_LENGTH);
                writeLong(out, len, scratch);
                out.write(CRLF);
            }
            out.write(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            out.write(CRLF);
            // EX-14: HEAD reports the Content-Length GET would have (above) but never writes
            // the body itself.
            if (body != null && !suppressBody) out.write(body);
        }
        out.flush();
    }

    private static void writeStreamingBody(OutputStream out, Response response, boolean keepAlive,
                                            boolean noContentAllowed, boolean suppressBody,
                                            ConnectionScratch scratch) throws IOException {
        if (!response.isChunked()) {
            if (!noContentAllowed) {
                out.write(CONTENT_LENGTH);
                writeLong(out, response.getStreamLength(), scratch);
                out.write(CRLF);
            }
            out.write(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            out.write(CRLF);
            if (!suppressBody) relay(response.getStream(), out, scratch);
        } else {
            out.write(TRANSFER_CHUNKED);
            out.write(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            out.write(CRLF);
            // A HEAD response still declares the Transfer-Encoding GET would have used (RFC
            // 9110 §9.3.2) but writes zero body bytes — not even the final-chunk marker, since
            // there is no chunk framing at all for a message with no body.
            if (!suppressBody) writeChunked(out, response.getStream(), scratch);
        }
    }

    /**
     * Copies {@code in} to {@code out} until EOF, via {@link ConnectionScratch#relayBuffer}
     * instead of a fresh {@code byte[]} per call.
     */
    private static void relay(InputStream in, OutputStream out, ConnectionScratch scratch) throws IOException {
        byte[] buf = scratch.relayBuffer;
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private static void writeStatusPhrase(OutputStream out, int statusCode, ConnectionScratch scratch) throws IOException {
        byte[] phrase = HttpStatus.bytesForCode(statusCode);
        if (phrase != null) out.write(phrase);
        else { writeLong(out, statusCode, scratch); out.write(UNKNOWN_STATUS_SUFFIX); }
    }

    private static void writeLong(OutputStream out, long value, ConnectionScratch scratch) throws IOException {
        if (value == 0) { out.write('0'); return; }
        byte[] buf = scratch.decimalBuffer;
        int pos = buf.length;
        boolean neg = value < 0;
        if (neg) value = -value;
        do { buf[--pos] = (byte) ('0' + value % 10); value /= 10; } while (value > 0);
        if (neg) buf[--pos] = '-';
        out.write(buf, pos, buf.length - pos);
    }

    private static void writeChunked(OutputStream out, InputStream stream, ConnectionScratch scratch) throws IOException {
        byte[] buf = scratch.relayBuffer;
        int n;
        while ((n = stream.read(buf)) > 0) {
            writeHex(out, n);
            out.write(CRLF);
            out.write(buf, 0, n);
            out.write(CRLF);
        }
        out.write(FINAL_CHUNK);
    }

    private static void writeHex(OutputStream out, int value) throws IOException {
        int shift = 28;
        boolean leading = true;
        while (shift >= 0) {
            int digit = (value >>> shift) & 0xF;
            if (digit != 0 || !leading) {
                leading = false;
                out.write(digit < 10 ? '0' + digit : 'a' + digit - 10);
            }
            shift -= 4;
        }
        if (leading) out.write('0');
    }
}
