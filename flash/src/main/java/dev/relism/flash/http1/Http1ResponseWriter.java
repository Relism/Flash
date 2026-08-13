package dev.relism.flash.http1;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.DateHeader;
import dev.relism.flash.http.Http1Limits;
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
 * <h3>{@code EX-27}: one bulk write, not ~10 small ones</h3>
 * The status line, {@code Content-Type}, {@code Date}, every custom header, and
 * {@code Content-Length}/{@code Connection} are all serialized into
 * {@link ConnectionScratch#responseHead} (a reused {@link ByteWriter}) before a single
 * {@code OutputStream.write} call — not one small {@code write} per field, and no
 * {@link java.io.BufferedOutputStream} coalescing them at the stream layer (this class removes
 * the need for one entirely on the h1 response path). A body at or below
 * {@link Http1Limits#INLINE_BODY_THRESHOLD} is copied into the same scratch buffer and goes out
 * in that same syscall; a larger body is written separately right after, since copying it into
 * the head buffer first would cost an extra full-body memcpy the syscall it saves does not pay
 * for. Streaming/chunked bodies write the head, then relay their own bytes as they arrive — by
 * definition unknown or too large to fold into one buffer up front.
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

        ByteWriter head = scratch.responseHead;
        head.reset();
        head.writeBytes(HTTP_1_1);
        byte[] statusBytes = response.getStatusBytes();
        if (statusBytes != null) head.writeBytes(statusBytes);
        else writeStatusPhrase(head, statusCode);
        head.writeBytes(CRLF);

        // EX-15: a Content-Type of ContentType.NONE (empty byte[]) used to still emit the line
        // "Content-Type: \r\n" — a header with no value. Skip the line entirely instead.
        byte[] contentType = response.getContentType();
        if (contentType != null && contentType.length > 0) {
            head.writeBytes(CONTENT_TYPE);
            head.writeBytes(contentType);
            head.writeBytes(CRLF);
        }

        // EX-16: precomputed once per second by a shared daemon thread — one volatile read,
        // one write into the scratch, never a per-response format call.
        if (sendDate) head.writeBytes(DateHeader.bytes());

        response.writeHeadersInto(head);

        if (response.isStreaming()) {
            writeStreamingBody(out, head, response, keepAlive, noContentAllowed, suppressBody, scratch);
        } else {
            byte[] body = response.getBody();
            int len = body != null ? body.length : 0;
            if (!noContentAllowed) {
                head.writeBytes(CONTENT_LENGTH);
                head.writeDecimal(len);
                head.writeBytes(CRLF);
            }
            head.writeBytes(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            head.writeBytes(CRLF);

            // EX-14: HEAD reports the Content-Length GET would have (above) but never writes
            // the body itself.
            boolean writeBody = body != null && !suppressBody;
            if (writeBody && len <= Http1Limits.INLINE_BODY_THRESHOLD) {
                // EX-27: small body folded into the same scratch buffer — head + body leave in
                // one syscall.
                head.writeBytes(body);
                out.write(head.array(), 0, head.length());
            } else {
                out.write(head.array(), 0, head.length());
                if (writeBody) out.write(body);
            }
        }
        out.flush();
    }

    private static void writeStreamingBody(OutputStream out, ByteWriter head, Response response, boolean keepAlive,
                                            boolean noContentAllowed, boolean suppressBody,
                                            ConnectionScratch scratch) throws IOException {
        if (!response.isChunked()) {
            if (!noContentAllowed) {
                head.writeBytes(CONTENT_LENGTH);
                head.writeDecimal(response.getStreamLength());
                head.writeBytes(CRLF);
            }
            head.writeBytes(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            head.writeBytes(CRLF);
            out.write(head.array(), 0, head.length());
            if (!suppressBody) relay(response.getStream(), out, scratch);
        } else {
            head.writeBytes(TRANSFER_CHUNKED);
            head.writeBytes(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            head.writeBytes(CRLF);
            out.write(head.array(), 0, head.length());
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

    private static void writeStatusPhrase(ByteWriter head, int statusCode) {
        byte[] phrase = HttpStatus.bytesForCode(statusCode);
        if (phrase != null) head.writeBytes(phrase);
        else { head.writeDecimal(statusCode); head.writeBytes(UNKNOWN_STATUS_SUFFIX); }
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
