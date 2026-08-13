package dev.relism.flash;

import dev.relism.flash.bytes.ByteScan;
import dev.relism.flash.exceptions.MalformedRequestException;
import dev.relism.flash.http.Http1Limits;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Http1HeaderMap;
import dev.relism.flash.models.MutableHeaderMap;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestBody;
import dev.relism.flash.models.RequestLine;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathViews;
import dev.relism.flash.transport.BufferedByteSource;

import lombok.extern.slf4j.Slf4j;

import javax.net.ssl.SSLSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * One instance per connection. The buffer is allocated once and reused across
 * keep-alive requests. Grows on demand (doubling, up to {@code maxHeaderBufferSize}).
 *
 * <h3>Zero-allocation design</h3>
 * <ul>
 *   <li>No {@link String} allocations during parsing: paths, headers and
 *       protocol are exposed as {@link dev.relism.fpr.core.ByteView} slices
 *       into the shared buffer.</li>
 *   <li>{@link #headerMap} is reset in-place per request — single allocation
 *       for the lifetime of the connection.</li>
 *   <li>Pipelining / keep-alive leftover bytes are tracked via {@code bufBase}
 *       and {@code bufLen} — no copy between requests on the common path.</li>
 * </ul>
 *
 * <h3>State invariant</h3>
 * {@code bufBase} and {@code bufLen} always reflect unconsumed bytes that belong
 * to the <em>next</em> request. They are snapshotted at the top of {@link #parse}
 * and reset to {@code 0/0} before any work begins, so an exception thrown mid-parse
 * leaves the fields clean rather than pointing at stale data from a previous request.
 *
 * Anything wrong with the request itself — smuggling-relevant ambiguity, an over-limit
 * header, a malformed byte where the grammar forbids one — is reported as a
 * {@link MalformedRequestException} carrying the exact status the caller must respond with.
 * This is distinct from {@link IOException}, which still means "the socket failed" (EOF,
 * reset, timeout). The caller ({@code HttpServer.process}) must always close the connection
 * after a {@link MalformedRequestException}, never keep it alive — RFC 9112 §6.1's rationale
 * for rejecting {@code Content-Length} + {@code Transfer-Encoding} outright is exactly that a
 * kept-alive connection after a disputed request boundary is what a smuggling attack needs.
 */
@Slf4j
public class RequestParser {
    private static final int INITIAL_BUFFER_SIZE = 8192;

    private final int               maxHeaderBufferSize;
    private final InetSocketAddress remoteAddress;
    private final SSLSocket         sslSocket;
    private final Http1HeaderMap    headerMap   = new Http1HeaderMap();
    private final MutableHeaderMap  trailerMap  = new MutableHeaderMap();
    // request — same idiom as headerMap above.
    private final RequestLine       requestLine = new RequestLine();
    private final Request           request     = new Request();
    private final RequestBody       requestBody = new RequestBody();
    // RequestLine/Response themselves. queryView is only reset and used when a query string is
    // actually present; RequestLine.getQuery() must keep returning null otherwise (see reset()).
    private final FastPathViews.RequestByteView pathView     = new FastPathViews.RequestByteView(null, 0, 0);
    private final FastPathViews.RequestByteView queryView    = new FastPathViews.RequestByteView(null, 0, 0);
    private final FastPathViews.RequestByteView protocolView = new FastPathViews.RequestByteView(null, 0, 0);
    private byte[] buffer;

    // Unconsumed bytes belonging to the NEXT request.
    // Reset to 0/0 at the start of every parse() call — see invariant above.
    private int bufBase = 0;
    private int bufLen  = 0;

    public RequestParser() { this(64 * 1024, null, null); }
    public RequestParser(int maxHeaderBufferSize) { this(maxHeaderBufferSize, null, null); }
    public RequestParser(int maxHeaderBufferSize, InetSocketAddress remoteAddress) {
        this(maxHeaderBufferSize, remoteAddress, null);
    }

    /** {@code sslSocket} is {@code null} for a plain connection — see {@link Request#isSecure()}. */
    public RequestParser(int maxHeaderBufferSize, InetSocketAddress remoteAddress, SSLSocket sslSocket) {
        this.maxHeaderBufferSize = maxHeaderBufferSize;
        this.remoteAddress       = remoteAddress;
        this.sslSocket           = sslSocket;
        this.buffer = new byte[Math.min(INITIAL_BUFFER_SIZE, maxHeaderBufferSize)];
    }

    /**
     * Whether bytes from a previous {@link #parse} call are already buffered and ready to be
     * consumed by the next call without reading anything further from the source — the HTTP
     * pipelining case. The caller (the connection loop) uses this to decide whether it is safe
     * to skip waiting for "the next request has started arriving": if bytes are already
     * buffered, the next request has, by definition, already started (and may even be
     * complete), so an idle-timeout wait on the underlying source would wait for bytes that
     * were never going to arrive there — they are already here.
     */
    public boolean hasBufferedBytes() {
        return bufLen > 0;
    }

    /**
     * Parses the next HTTP request from {@code in}.
     *
     * <p><b>Exception safety:</b> {@code bufBase} and {@code bufLen} are reset
     * to {@code 0} before any parsing work begins. If an exception is thrown,
     * the connection will be closed by the caller, so stale leftover state is
     * never a problem — but the reset ensures correctness in test scenarios where
     * the same parser instance is reused after an error.
     *
     * @return the parsed {@link Request}, or {@code null} on clean EOF.
     * @throws MalformedRequestException if the request violates the HTTP/1.1 grammar or a
     *         configured safety limit — carries the exact status to respond with.
     * @throws IOException on genuine I/O failure (socket reset, timeout).
     */
    public Request parse(BufferedByteSource in) throws IOException {
        trailerMap.reset();
        // Snapshot leftover bytes from the previous request, then reset immediately.
        // Any exception thrown below leaves bufBase/bufLen at 0 — safe state.
        int base      = bufBase;
        int totalRead = bufLen;
        bufBase = 0;
        bufLen  = 0;

        int headerEndIdx = totalRead > 0 ? ByteScan.indexOfCrLfCrLf(buffer, base, base + totalRead) : -1;
        while (headerEndIdx == -1) {
            if (base + totalRead == buffer.length) {
                if (base > 0) {
                    // Compact: slide valid data to position 0.
                    // Rare path (~every N requests where N ≈ bufferSize / avgRequestSize).
                    System.arraycopy(buffer, base, buffer, 0, totalRead);
                    base = 0;
                } else if (buffer.length >= maxHeaderBufferSize) {
                    throw new MalformedRequestException(431,
                            "Request headers exceed " + maxHeaderBufferSize + " bytes");
                } else {
                    buffer = Arrays.copyOf(buffer, Math.min(buffer.length * 2, maxHeaderBufferSize));
                }
            }
            int n = in.read(buffer, base + totalRead, buffer.length - base - totalRead);
            if (n <= 0) break;
            int prevTotal = totalRead;
            totalRead += n;
            headerEndIdx = ByteScan.indexOfCrLfCrLf(buffer, base + Math.max(0, prevTotal - 3), base + totalRead);
        }
        if (totalRead <= 0) return null;
        if (headerEndIdx == -1) {
            throw new MalformedRequestException(431,
                    "Request headers exceed " + maxHeaderBufferSize + " bytes");
        }

        // ── Request line ─────────────────────────────────────────────────────

        int methodEnd = ByteScan.indexOf(buffer, base, headerEndIdx, (byte) ' ');
        if (methodEnd == -1) throw new MalformedRequestException(400, "Invalid request line (method)");
        if (methodEnd == base) throw new MalformedRequestException(400, "Missing HTTP method");

        HttpMethod method = HttpMethod.fromBytes(buffer, base, methodEnd - base);
        if (method == null) throw new MalformedRequestException(501, "Unsupported HTTP method");

        int pathStart = methodEnd + 1;
        int pathEnd   = ByteScan.indexOf(buffer, pathStart, headerEndIdx, (byte) ' ');
        if (pathEnd == -1) throw new MalformedRequestException(400, "Invalid request line (path)");

        int queryMark = ByteScan.indexOf(buffer, pathStart, pathEnd, (byte) '?');
        pathView.reset(buffer, pathStart, queryMark != -1 ? queryMark - pathStart : pathEnd - pathStart);
        if (queryMark != -1) queryView.reset(buffer, queryMark + 1, pathEnd - queryMark - 1);

        int protocolStart = pathEnd + 1;
        int protocolEnd   = ByteScan.indexOf(buffer, protocolStart, headerEndIdx, (byte) '\r');
        if (protocolEnd == -1) throw new MalformedRequestException(400, "Invalid request line (protocol)");

        // from the overall header-block size, so an oversized request line gets its own,
        // specific rejection rather than being folded into the generic "headers too large" case.
        if (protocolEnd - base > Http1Limits.MAX_REQUEST_LINE_LENGTH) {
            throw new MalformedRequestException(431, "Request line exceeds " + Http1Limits.MAX_REQUEST_LINE_LENGTH + " bytes");
        }

        protocolView.reset(buffer, protocolStart, protocolEnd - protocolStart);

        // ── Headers ──────────────────────────────────────────────────────────

        int sectionStart = ByteScan.indexOf(buffer, protocolEnd, headerEndIdx, (byte) '\n') + 1;
        int current      = sectionStart;
        long contentLength          = -1;
        boolean contentLengthSeen   = false;
        boolean transferEncodingSeen    = false;
        boolean transferEncodingChunked = false;
        int headerCount = 0;

        while (current < headerEndIdx) {
            // deprecates line folding and treating a folded continuation as part of the
            // previous header's value is a known request-smuggling vector.
            byte first = buffer[current];
            if (first == ' ' || first == '\t') {
                throw new MalformedRequestException(400, "Obsolete line folding is not supported");
            }

            int lineEnd = ByteScan.indexOf(buffer, current, headerEndIdx + 1, (byte) '\r');
            if (lineEnd == -1 || lineEnd == current) break;

            // advancing past two bytes — a bare '\r' not followed by '\n' desynchronizes the
            // parse and is a known bare-CR smuggling surface. Safe to read lineEnd+1: lineEnd
            // is at most headerEndIdx, and findEndOfHeader already guaranteed 4 readable bytes
            // (\r\n\r\n) starting at headerEndIdx.
            if (buffer[lineEnd + 1] != '\n') {
                throw new MalformedRequestException(400, "Malformed line terminator (bare CR)");
            }

            if (++headerCount > Http1Limits.MAX_HEADER_COUNT) {
                throw new MalformedRequestException(431, "Too many headers");
            }

            int colon = ByteScan.indexOf(buffer, current, lineEnd, (byte) ':');
            if (colon == -1) {
                throw new MalformedRequestException(400, "Header line missing ':'");
            }
            if (colon - current > Http1Limits.MAX_HEADER_NAME_LENGTH) {
                throw new MalformedRequestException(431, "Header name exceeds " + Http1Limits.MAX_HEADER_NAME_LENGTH + " bytes");
            }
            for (int i = current; i < colon; i++) {
                if (!ByteScan.isTChar(buffer[i])) {
                    throw new MalformedRequestException(400, "Invalid header name character");
                }
            }

            int valueStart = colon + 1;
            while (valueStart < lineEnd && buffer[valueStart] == ' ') valueStart++;
            if (lineEnd - valueStart > Http1Limits.MAX_HEADER_VALUE_LENGTH) {
                throw new MalformedRequestException(431, "Header value exceeds " + Http1Limits.MAX_HEADER_VALUE_LENGTH + " bytes");
            }

            if (ByteScan.equalsIgnoreCaseAscii(buffer, current, colon, "content-length")) {
                // parseLong, which silently accepted "5abc" as 5 and "-1" as 1.
                long parsed = parseContentLengthStrict(buffer, valueStart, lineEnd);
                // Multiple Content-Length lines with differing values is itself a smuggling
                // permits a recipient to treat that as one value).
                if (contentLengthSeen && parsed != contentLength) {
                    throw new MalformedRequestException(400, "Conflicting Content-Length values");
                }
                contentLength = parsed;
                contentLengthSeen = true;
            } else if (ByteScan.equalsIgnoreCaseAscii(buffer, current, colon, "transfer-encoding")) {
                transferEncodingSeen = true;
                // "chunked", so "gzip, chunked" — valid per RFC 9112 §6.1, where chunked need
                // only be the FINAL coding — was silently treated as not chunked at all,
                // corrupting the message boundary. Fixed by inspecting only the last token.
                transferEncodingChunked = isFinalCodingChunked(buffer, valueStart, lineEnd);
            }
            current = lineEnd + 2;
        }

        // MUST be treated as an error by an origin server — this is the canonical CL.TE/TE.CL
        // smuggling vector. Checked once both headers are known, regardless of the order they
        // appeared in, so ordering games cannot bypass it.
        if (contentLengthSeen && transferEncodingSeen) {
            throw new MalformedRequestException(400, "Content-Length and Transfer-Encoding both present");
        }
        boolean isChunked;
        if (transferEncodingSeen) {
            if (!transferEncodingChunked) {
                throw new MalformedRequestException(501, "Unsupported Transfer-Encoding");
            }
            isChunked = true;
        } else {
            isChunked = false;
            if (!contentLengthSeen) contentLength = 0;
        }

        headerMap.reset(buffer, sectionStart, headerEndIdx);

        // ── Body / pipelining accounting ─────────────────────────────────────

        int bodyStart = headerEndIdx + 4;
        int preBufLen = (base + totalRead) - bodyStart;

        // Bytes read beyond this request's body belong to the next request.
        // Store their absolute position — no copy needed; the next parse() call
        // reads directly from bufBase without touching the data.
        if (!isChunked && contentLength == 0 && preBufLen > 0) {
            bufBase   = bodyStart;
            bufLen    = preBufLen;
            preBufLen = 0;
        } else if (!isChunked && contentLength > 0 && preBufLen > contentLength) {
            bufBase   = bodyStart + (int) contentLength;
            bufLen    = preBufLen - (int) contentLength;
            preBufLen = (int) contentLength;
        }

        requestLine.reset(method, pathView, queryMark != -1 ? queryView : null, protocolView, headerMap);

        // Javadoc) -- reset() repositions it for the fixed-length/empty case (contentLength == 0
        // is handled by the same call: preBufLen is already forced to 0 for it above) or the
        // chunked case, never reallocated.
        if (isChunked) {
            requestBody.reset(
                    new ChunkedInputStream(in, buffer, bodyStart, preBufLen, trailerMap),
                    -1L, null, 0, 0);
        } else {
            requestBody.reset(in, contentLength, buffer, bodyStart, preBufLen);
        }
        Request parsed = Request.forParsed(request, requestLine, requestBody, remoteAddress, sslSocket);
        parsed.setTrailers(trailerMap);
        return parsed;
    }

    /**
     * value, any non-digit byte (including a leading {@code +}/{@code -}, which are not
     * digits), more than 19 digits (the longest possible {@code Long.MAX_VALUE}), arithmetic
     * overflow past {@code Long.MAX_VALUE}, and a value above
     * {@link Http1Limits#MAX_CONTENT_LENGTH}. The pre-existing {@code parseLong} silently
     * skipped any non-digit character instead of rejecting it — {@code "5abc"} parsed as
     * {@code 5} and {@code "-1"} parsed as {@code 1}.
     */
    private static long parseContentLengthStrict(byte[] buf, int start, int end) throws MalformedRequestException {
        int len = end - start;
        if (len == 0) throw new MalformedRequestException(400, "Empty Content-Length value");
        if (len > 19) throw new MalformedRequestException(400, "Content-Length value too long");
        long value = 0;
        for (int i = start; i < end; i++) {
            byte c = buf[i];
            if (c < '0' || c > '9') {
                throw new MalformedRequestException(400, "Malformed Content-Length value");
            }
            int digit = c - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                throw new MalformedRequestException(400, "Content-Length overflow");
            }
            value = value * 10 + digit;
        }
        if (value > Http1Limits.MAX_CONTENT_LENGTH) {
            throw new MalformedRequestException(413, "Content-Length exceeds configured maximum");
        }
        return value;
    }

    /**
     * RFC 9112 §6.1: when {@code Transfer-Encoding} lists multiple codings
     * ({@code "gzip, chunked"}), {@code chunked} MUST be the final one for the message to be
     * self-delimiting. Returns whether the last comma-separated token in {@code [start, end)}
     * is exactly {@code "chunked"} (case-insensitive), ignoring surrounding whitespace around
     * misclassified any multi-coding value as non-chunked.
     */
    private static boolean isFinalCodingChunked(byte[] buf, int start, int end) {
        int e = end;
        while (e > start && (buf[e - 1] == ' ' || buf[e - 1] == '\t')) e--;
        int lastComma = start - 1;
        for (int i = start; i < e; i++) {
            if (buf[i] == ',') lastComma = i;
        }
        int tokenStart = lastComma + 1;
        while (tokenStart < e && (buf[tokenStart] == ' ' || buf[tokenStart] == '\t')) tokenStart++;
        return ByteScan.equalsIgnoreCaseAscii(buf, tokenStart, e, "chunked");
    }
}
