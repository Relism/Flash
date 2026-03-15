package dev.relism;

import dev.relism.models.HeaderMap;
import dev.relism.http.HttpMethod;
import dev.relism.models.Request;
import dev.relism.models.RequestLine;
import dev.relism.routing.routers.fastpathrouter.FastPathViews;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;

/**
 * Extreme Zero-Allocation Request Parser.
 * Scans raw bytes to identify paths, protocols, and headers without intermediate String objects.
 * Uses buffered reading and direct byte comparisons for maximum performance.
 */
@Slf4j
public class RequestParser {
    private static final int MAX_HEADER_SIZE = 8192;

    public static Request parse(InputStream in) throws IOException {
        byte[] buffer = new byte[MAX_HEADER_SIZE];

        // 1. Robust read loop — accumulate until \r\n\r\n found or buffer full.
        // prevTotal tracked per iteration so findEndOfHeader starts from max(0, prevTotal-3),
        // skipping already-scanned bytes. The -3 overlap catches \r\n\r\n split across two reads.
        int totalRead = 0;
        int headerEndIdx = -1;
        while (totalRead < buffer.length) {
            int n = in.read(buffer, totalRead, buffer.length - totalRead);
            if (n <= 0) break;
            int prevTotal = totalRead;
            totalRead += n;
            headerEndIdx = findEndOfHeader(buffer, Math.max(0, prevTotal - 3), totalRead);
            if (headerEndIdx != -1) break;
        }
        if (totalRead <= 0) return null;
        if (headerEndIdx == -1) {
            throw new IOException("Headers too large: buffer exhausted without finding \\r\\n\\r\\n");
        }

        // 2. Scan Request Line: METHOD PATH PROTOCOL
        int methodEnd = find(buffer, 0, headerEndIdx, (byte) ' ');
        if (methodEnd == -1) throw new IOException("Invalid request line (method)");

        HttpMethod method = HttpMethod.fromBytes(buffer, 0, methodEnd);
        if (method == null) throw new IOException("Unsupported HTTP method");

        int pathStart = methodEnd + 1;
        int pathEnd   = find(buffer, pathStart, headerEndIdx, (byte) ' ');
        if (pathEnd == -1) throw new IOException("Invalid request line (path)");

        // Split path from query string at '?' — FPR only sees the clean path
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

        // 3. Scan Headers — store raw offsets into buffer, zero objects allocated per header
        HeaderMap headerMap = new HeaderMap(buffer);
        int current = find(buffer, protocolEnd, headerEndIdx, (byte) '\n') + 1;
        int contentLength = 0;

        while (current < headerEndIdx) {
            int lineEnd = find(buffer, current, headerEndIdx + 1, (byte) '\r');
            if (lineEnd == -1 || lineEnd == current) break;

            int colon = find(buffer, current, lineEnd, (byte) ':');
            if (colon != -1) {
                int valueStart = colon + 1;
                while (valueStart < lineEnd && buffer[valueStart] == ' ') valueStart++;

                headerMap.add(current, colon - current, valueStart, lineEnd - valueStart);

                // Direct byte comparison to extract Content-Length without String allocation
                if (equalsIgnoreCase(buffer, current, colon, "content-length")) {
                    contentLength = parseInt(buffer, valueStart, lineEnd);
                }
            }
            current = lineEnd + 2; // skip \r\n
        }

        // Body is read lazily — only materialized if the handler calls req.getBody().
        // Bytes already in the buffer past \r\n\r\n are handed off to LazyBody as read-ahead.
        int bodyStart = headerEndIdx + 4;
        int preBufLen = totalRead - bodyStart;
        return Request.forParsed(
                new RequestLine(method, pathView, queryView, protocolView, headerMap),
                in, contentLength, buffer, bodyStart, preBufLen);
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

    private static int parseInt(byte[] buf, int start, int end) {
        int value = 0;
        for (int i = start; i < end; i++) {
            byte c = buf[i];
            if (c >= '0' && c <= '9') value = value * 10 + (c - '0');
        }
        return value;
    }
}
