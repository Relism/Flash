package dev.relism;

import dev.relism.http.HttpMethod;
import dev.relism.models.HeaderMap;
import dev.relism.models.Request;
import dev.relism.models.RequestLine;
import dev.relism.routing.routers.fastpathrouter.FastPathViews;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.Arrays;

/**
 * One instance per connection, the buffer is allocated once and reused across keep-alive
 * requests. Grows on demand (doubling, up to {@code maxHeaderBufferSize}). Zero String
 * allocations during parsing; paths, headers and protocol are exposed as {@link dev.relism.fpr.core.ByteView} slices.
 */
@Slf4j
public class RequestParser {
    private static final int INITIAL_BUFFER_SIZE = 8192;

    private final int             maxHeaderBufferSize;
    private final InetSocketAddress remoteAddress;        // set once per connection, never changes
    private final HeaderMap       headerMap = new HeaderMap();
    private byte[] buffer;
    private int bufBase = 0;  // absolute start of valid data in buffer
    private int bufLen  = 0;  // number of valid bytes from bufBase

    public RequestParser() { this(64 * 1024, null); }
    public RequestParser(int maxHeaderBufferSize) { this(maxHeaderBufferSize, null); }
    public RequestParser(int maxHeaderBufferSize, InetSocketAddress remoteAddress) {
        this.maxHeaderBufferSize = maxHeaderBufferSize;
        this.remoteAddress       = remoteAddress;
        this.buffer = new byte[Math.min(INITIAL_BUFFER_SIZE, maxHeaderBufferSize)];
    }

    public Request parse(InputStream in) throws IOException {
        // Take ownership of any leftover bytes from the previous request, then reset so
        // early-returns leave the fields in a clean state.
        int base      = bufBase;
        int totalRead = bufLen;
        bufBase = 0;
        bufLen  = 0;

        int headerEndIdx = totalRead > 0 ? findEndOfHeader(buffer, base, base + totalRead) : -1;
        while (headerEndIdx == -1) {
            if (base + totalRead == buffer.length) {
                if (base > 0) {
                    // Compact: slide valid data to position 0 — rare path (~every N requests
                    // where N = bufferSize / avgRequestSize rather than every request).
                    System.arraycopy(buffer, base, buffer, 0, totalRead);
                    base = 0;
                } else if (buffer.length >= maxHeaderBufferSize) {
                    throw new IOException("Request headers exceed " + maxHeaderBufferSize + " bytes");
                } else {
                    buffer = Arrays.copyOf(buffer, Math.min(buffer.length * 2, maxHeaderBufferSize));
                }
            }
            int n = in.read(buffer, base + totalRead, buffer.length - base - totalRead);
            if (n <= 0) break;
            int prevTotal = totalRead;
            totalRead += n;
            headerEndIdx = findEndOfHeader(buffer, base + Math.max(0, prevTotal - 3), base + totalRead);
        }
        if (totalRead <= 0) return null;
        if (headerEndIdx == -1) {
            throw new IOException("Request headers exceed " + maxHeaderBufferSize + " bytes");
        }

        int methodEnd = find(buffer, base, headerEndIdx, (byte) ' ');
        if (methodEnd == -1) throw new IOException("Invalid request line (method)");

        HttpMethod method = HttpMethod.fromBytes(buffer, base, methodEnd - base);
        if (method == null) throw new IOException("Unsupported HTTP method");

        int pathStart = methodEnd + 1;
        int pathEnd   = find(buffer, pathStart, headerEndIdx, (byte) ' ');
        if (pathEnd == -1) throw new IOException("Invalid request line (path)");

        int queryMark = find(buffer, pathStart, pathEnd, (byte) '?');
        FastPathViews.RequestByteView pathView  = new FastPathViews.RequestByteView(buffer, pathStart,
                queryMark != -1 ? queryMark - pathStart : pathEnd - pathStart);
        FastPathViews.RequestByteView queryView = queryMark != -1
                ? new FastPathViews.RequestByteView(buffer, queryMark + 1, pathEnd - queryMark - 1)
                : null;

        int protocolStart = pathEnd + 1;
        int protocolEnd = find(buffer, protocolStart, headerEndIdx, (byte) '\r');
        if (protocolEnd == -1) throw new IOException("Invalid request line (protocol)");

        FastPathViews.RequestByteView protocolView = new FastPathViews.RequestByteView(buffer, protocolStart, protocolEnd - protocolStart);

        int sectionStart = find(buffer, protocolEnd, headerEndIdx, (byte) '\n') + 1;
        int current = sectionStart;
        long contentLength = 0;
        boolean isChunked  = false;

        while (current < headerEndIdx) {
            int lineEnd = find(buffer, current, headerEndIdx + 1, (byte) '\r');
            if (lineEnd == -1 || lineEnd == current) break;

            int colon = find(buffer, current, lineEnd, (byte) ':');
            if (colon != -1) {
                int valueStart = colon + 1;
                while (valueStart < lineEnd && buffer[valueStart] == ' ') valueStart++;

                if (equalsIgnoreCase(buffer, current, colon, "content-length")) {
                    contentLength = parseLong(buffer, valueStart, lineEnd);
                } else if (equalsIgnoreCase(buffer, current, colon, "transfer-encoding")) {
                    isChunked = equalsIgnoreCase(buffer, valueStart, lineEnd, "chunked");
                }
            }
            current = lineEnd + 2;
        }

        headerMap.reset(buffer, sectionStart, headerEndIdx);

        int bodyStart = headerEndIdx + 4;
        int preBufLen = (base + totalRead) - bodyStart;

        // Any bytes read beyond this request's body belong to the next request.
        // Store their absolute position in the buffer — no copy needed; the next parse()
        // call will read directly from bufBase without touching the data.
        if (!isChunked && contentLength == 0 && preBufLen > 0) {
            bufBase = bodyStart;
            bufLen  = preBufLen;
            preBufLen = 0;
        } else if (!isChunked && contentLength > 0 && preBufLen > contentLength) {
            bufBase = bodyStart + (int) contentLength;
            bufLen  = preBufLen - (int) contentLength;
            preBufLen = (int) contentLength;
        }

        RequestLine requestLine = new RequestLine(method, pathView, queryView, protocolView, headerMap);

        if (isChunked) {
            return Request.forParsed(requestLine, new ChunkedInputStream(in, buffer, bodyStart, preBufLen), -1L, null, 0, 0, remoteAddress);
        }
        return Request.forParsed(requestLine, in, contentLength, buffer, bodyStart, preBufLen, remoteAddress);
    }

    private static int findEndOfHeader(byte[] buf, int from, int len) {
        for (int i = from; i <= len - 4; i++) {
            if (buf[i] == '\r' && buf[i+1] == '\n' && buf[i+2] == '\r' && buf[i+3] == '\n') {
                return i;
            }
        }
        return -1;
    }

    private static int find(byte[] buf, int start, int end, byte target) {
        for (int i = start; i < end; i++) {
            if (buf[i] == target) return i;
        }
        return -1;
    }

    private static boolean equalsIgnoreCase(byte[] buf, int start, int end, String target) {
        int len = end - start;
        if (len != target.length()) return false;
        for (int i = 0; i < len; i++) {
            byte b = buf[start + i];
            if (b >= 'A' && b <= 'Z') b += 32;
            if (b != (byte) target.charAt(i)) return false;
        }
        return true;
    }

    private static long parseLong(byte[] buf, int start, int end) {
        long value = 0;
        for (int i = start; i < end; i++) {
            byte c = buf[i];
            if (c >= '0' && c <= '9') value = value * 10 + (c - '0');
        }
        return value;
    }
}
