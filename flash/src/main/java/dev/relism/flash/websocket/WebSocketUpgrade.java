package dev.relism.flash.websocket;

import dev.relism.flash.http1.Http1KeepAlive;
import dev.relism.flash.models.Request;
import dev.relism.flash.transport.ConnectionScratch;
import dev.relism.fpr.core.ByteView;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/**
 * WebSocket upgrade detection (RFC 6455 §4.2.1) and handshake response. Extracted from
 * {@code HttpServer} (Phase 2) — its only responsibility is deciding whether a request is an
 * upgrade request and, if so, answering the {@code 101 Switching Protocols} handshake. The
 * session loop itself lives in {@link WebSocketLoop}.
 */
public final class WebSocketUpgrade {

    private WebSocketUpgrade() {
    }

    private static final byte[] WS_HANDSHAKE_PREFIX =
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ")
                    .getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] WS_HANDSHAKE_SUFFIX =
            "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);

    public static final byte[] REJECT_400 =
            "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] WS_GUID_BYTES =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.ISO_8859_1);

    /**
     * Whether {@code request} is a WebSocket upgrade request: {@code Upgrade: websocket} and a
     * {@code Connection} header whose token list includes {@code upgrade} ({@code EX-13} — the
     * shared token-list scanner in {@link Http1KeepAlive} is what fixed the whole-value compare
     * bug this check used to have too).
     */
    public static boolean isWebSocketUpgrade(Request request) {
        ByteView upgrade = request.getRequestLine().getHeaders().view("Upgrade");
        if (upgrade == null) return false;
        if (!Http1KeepAlive.tokenEqualsIgnoreCase(upgrade, 0, upgrade.length(), "websocket")) return false;
        return Http1KeepAlive.connectionContainsToken(request, "upgrade");
    }

    /** Writes and flushes the {@code 101 Switching Protocols} handshake response. */
    public static void performHandshake(OutputStream out, Request request, ConnectionScratch scratch) throws IOException {
        ByteView keyView = request.getRequestLine().getHeaders().view("Sec-WebSocket-Key");
        if (keyView == null) throw new IOException("Missing Sec-WebSocket-Key header");

        MessageDigest sha1 = scratch.sha1;
        sha1.reset();
        for (int i = 0, len = keyView.length(); i < len; i++) sha1.update(keyView.byteAt(i));
        sha1.update(WS_GUID_BYTES);

        byte[] accept = Base64.getEncoder().encode(sha1.digest());

        out.write(WS_HANDSHAKE_PREFIX);
        out.write(accept);
        out.write(WS_HANDSHAKE_SUFFIX);
        out.flush();
    }
}
