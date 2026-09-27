package dev.relism.flash.http1;

import dev.relism.flash.http.Request;
import dev.relism.fpr.core.ByteView;

/**
 * HTTP/1.1 keep-alive decision (RFC 9110 §7.6.1) and the shared {@code Connection} header
 * token-list scanner both it and WebSocket upgrade detection need.
 *
 * (e.g. {@code "Connection: keep-alive, Upgrade"}), not a single value — a whole-value compare
 * against {@code "close"} misses exactly that case. {@link #tokenListContains} is the one
 * scanner both this class's {@link #isKeepAlive} and {@code WebSocketUpgrade}'s
 * {@code Connection: Upgrade} check use, so the two can never drift apart again.
 */
public final class Http1KeepAlive {

    private Http1KeepAlive() {
    }

    /**
     * Whether the connection should remain open after this response. HTTP/1.1 defaults to
     * keep-alive unless {@code Connection} lists {@code close}; HTTP/1.0 defaults to close
     * unless it lists {@code keep-alive}.
     */
    public static boolean isKeepAlive(Request request) {
        if (connectionContainsToken(request, "close")) return false;
        ByteView protocol = request.requestLine().getProtocol();
        int plen = protocol.length();
        if (plen == 8) {
            byte minor = protocol.byteAt(7);
            if (minor == '1') return true;
            if (minor == '0') return connectionContainsToken(request, "keep-alive");
        }
        return false;
    }

    /** Whether the request's {@code Connection} header lists {@code token} (case-insensitive). */
    public static boolean connectionContainsToken(Request request, String token) {
        ByteView conn = request.requestLine().getHeaders().view("Connection");
        if (conn == null) return false;
        return tokenListContains(conn, token);
    }

    /** Scans a comma-separated token list for {@code token} (case-insensitive, OWS-tolerant). */
    public static boolean tokenListContains(ByteView view, String token) {
        int len = view.length(), i = 0;
        while (i < len) {
            while (i < len && view.byteAt(i) == ' ') i++;
            int start = i;
            while (i < len && view.byteAt(i) != ',') i++;
            if (tokenEqualsIgnoreCase(view, start, i, token)) return true;
            i++;
        }
        return false;
    }

    /** Case-insensitive compare of {@code view[start, end)}, trimming trailing spaces, against {@code token}. */
    public static boolean tokenEqualsIgnoreCase(ByteView view, int start, int end, String token) {
        int tlen = token.length();
        int wlen = end - start;
        while (wlen > 0 && view.byteAt(start + wlen - 1) == ' ') wlen--;
        if (wlen != tlen) return false;
        for (int i = 0; i < tlen; i++) {
            byte b = view.byteAt(start + i);
            if (b >= 'A' && b <= 'Z') b += 32;
            if (b != (byte) token.charAt(i)) return false;
        }
        return true;
    }
}
