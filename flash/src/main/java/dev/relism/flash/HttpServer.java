package dev.relism.flash;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.HttpStatus;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.AbstractWsRouter;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import dev.relism.fpr.core.ByteView;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure I/O transport layer. Owns the {@link ServerSocket}, the virtual-thread
 * executor, and the keep-alive accept loop. Routing is delegated to HTTP and WS routers.
 *
 * <h3>Allocation model (unchanged)</h3>
 * <ul>
 *   <li>{@code LONG_BUF} (20 bytes) is the only {@link ThreadLocal} kept here.</li>
 *   <li>WS handshake SHA-1: {@link ThreadLocal}&lt;{@link MessageDigest}&gt; — one per
 *       accept thread (there are now {@code ACCEPT_THREADS} of them, not one).</li>
 * </ul>
 */
@Slf4j
class HttpServer implements ServerHandle {

    // ── Tuning constants ──────────────────────────────────────────────────────

    /**
     * Number of platform threads competing on {@code serverSocket.accept()}.
     * Rule of thumb: number of available CPU cores, capped at 8.
     * More than this rarely helps — accept is cheap; the bottleneck is usually
     * the virtual-thread executor dispatching the connection handler.
     */
    private static final int ACCEPT_THREADS  = Math.min(Runtime.getRuntime().availableProcessors(), 8);

    /**
     * TCP listen backlog. The kernel holds up to this many fully-established
     * (SYN+ACK sent, ACK received) connections waiting for accept().
     * 4096 is safe on Linux; /proc/sys/net/core/somaxconn must be >= this value,
     * or the kernel silently caps it. Raise somaxconn if needed:
     *   sysctl -w net.core.somaxconn=4096
     */
    private static final int ACCEPT_BACKLOG  = 4096;

    /**
     * Socket send/receive buffer sizes. Matched to the WS frame read buffer
     * ({@link FlashConfiguration#getWsFrameBufferSize()}) so the kernel never
     * needs to fragment a full frame into multiple TCP segments on the receive
     * side, and never blocks a write waiting for the send buffer to drain.
     *
     * Linux default is 87380 bytes (rmem) / 16384 bytes (wmem). We raise both
     * to 256 KB — a good fit for up to ~250 KB WS frames with no partial reads.
     */
    private static final int SOCKET_BUF_SIZE = 256 * 1024;

    // ── Instance fields ───────────────────────────────────────────────────────

    private final FlashConfiguration  configuration;
    private final ServerSocket        serverSocket;
    private final AbstractRouter      router;
    private final AbstractWsRouter    wsRouter;
    private final ExecutorService     executorService  = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket>         activeSockets    = ConcurrentHashMap.newKeySet();
    private volatile boolean          stopped          = false;

    /** Latch that reaches 0 when all accept threads have exited. */
    private final CountDownLatch      acceptLatch      = new CountDownLatch(ACCEPT_THREADS);

    // ── Static byte constants (written once, read-only on hot path) ──────────

    private static final byte[] HTTP_1_1              = "HTTP/1.1 ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CRLF                  = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE          = "Content-Type: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_LENGTH        = "Content-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_CLOSE      = "Connection: close\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_KEEPALIVE  = "Connection: keep-alive\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_CHUNKED      = "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINAL_CHUNK           = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNKNOWN_STATUS_SUFFIX = " Unknown".getBytes(StandardCharsets.UTF_8);

    private static final byte[] WS_HANDSHAKE_PREFIX =
            ("HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: ")
                    .getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] WS_HANDSHAKE_SUFFIX =
            "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    private static final byte[] WS_REJECT_400 =
            "HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1);

    private static final byte[] WS_GUID_BYTES =
            "258EAFA5-E914-47DA-95CA-C5AB0DC85B11".getBytes(StandardCharsets.ISO_8859_1);

    private static final ThreadLocal<MessageDigest> SHA1 =
            ThreadLocal.withInitial(() -> {
                try { return MessageDigest.getInstance("SHA-1"); }
                catch (NoSuchAlgorithmException e) { throw new RuntimeException(e); }
            });

    private static final ThreadLocal<byte[]> LONG_BUF = ThreadLocal.withInitial(() -> new byte[20]);

    private static final int SHA1_LEN      = 20;
    private static final int WS_ACCEPT_LEN = 28;

    // ── Constructor ───────────────────────────────────────────────────────────

    HttpServer(FlashConfiguration configuration, AbstractRouter router, AbstractWsRouter wsRouter) throws IOException {
        this.configuration = configuration;
        this.router        = router;
        this.wsRouter      = wsRouter;

        // Explicit bind with raised backlog.
        // setReuseAddress(true) must be called BEFORE bind().
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.setReceiveBufferSize(SOCKET_BUF_SIZE);
        this.serverSocket.bind(new InetSocketAddress(configuration.getPort()), ACCEPT_BACKLOG);

        log.info("HttpServer bound on port {} (backlog={}, acceptThreads={})",
                configuration.getPort(), ACCEPT_BACKLOG, ACCEPT_THREADS);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void start() {
        for (int i = 0; i < ACCEPT_THREADS; i++) {
            final int idx = i;
            Thread.ofPlatform()
                    .name("flash-accept-" + idx)
                    .daemon(false)
                    .start(this::acceptLoop);
        }
    }

    @Override
    public void startAndBlock() {
        start();
        try { acceptLatch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /**
     * Single accept loop body — runs on each of the {@code ACCEPT_THREADS}
     * platform threads. All threads block on the same {@link ServerSocket};
     * the JVM ensures only one wakes per incoming connection (no thundering herd).
     */
    private void acceptLoop() {
        try {
            while (!stopped) {
                try {
                    process(serverSocket.accept());
                } catch (IOException e) {
                    if (!stopped) log.error("Accept error", e);
                }
            }
        } finally {
            acceptLatch.countDown();
        }
    }

    @Override
    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            stopped = true;
            try { serverSocket.close(); } catch (IOException e) { log.error("Error closing server socket", e); }
            activeSockets.forEach(s -> { try { s.close(); } catch (IOException ignored) {} });
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS))
                    executorService.shutdownNow();
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }

    // ── Hot-path ──────────────────────────────────────────────────────────────

    private void process(Socket socket) {
        try {
            executorService.submit(() -> {
                activeSockets.add(socket);
                try (socket;
                     InputStream  in  = socket.getInputStream();
                     OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

                    // TCP_NODELAY: disable Nagle's algorithm.
                    // Small WS frames (< MSS) are sent immediately rather than
                    // waiting up to 200 ms for more data to coalesce. Latency
                    // drops significantly at the cost of slightly more TCP segments
                    // under sustained bulk transfer — acceptable for interactive WS.
                    socket.setTcpNoDelay(true);
                    socket.setSendBufferSize(SOCKET_BUF_SIZE);

                    // rawOut is the unbuffered socket stream — passed to WebSocketSession
                    // directly. WS writes are already bulk (header + payload in two calls);
                    // with TCP_NODELAY the kernel ships them without Nagle delay, so no
                    // userspace buffer is needed and no flush() is required per frame.
                    // HTTP responses continue to use the BufferedOutputStream (out) because
                    // writeResponse() does many small individual writes that benefit from
                    // userspace coalescing before a single syscall.
                    OutputStream rawOut = socket.getOutputStream();

                    RequestParser parser = new RequestParser(
                            configuration.getMaxHeaderBufferSize(),
                            (InetSocketAddress) socket.getRemoteSocketAddress());

                    while (!stopped) {
                        Request request = parser.parse(in);
                        if (request == null) break;

                        if (request.method() == HttpMethod.GET && isWebSocketUpgrade(request)) {
                            WebSocketHandler wsHandler = wsRouter.route(request);
                            if (wsHandler == null) {
                                out.write(WS_REJECT_400);
                                out.flush();
                                break;
                            }
                            // Flush buffered HTTP bytes (the 101 response) before WebSocketSession
                            // takes over rawOut — otherwise the handshake reply stays stuck in
                            // the BufferedOutputStream buffer and the client never sees it.
                            performHandshake(out, request);
                            out.flush();
                            request.drain();
                            WebSocketSession session = new WebSocketSession(
                                    in, rawOut, configuration.getWsFrameBufferSize());
                            runWsLoop(session, wsHandler);
                            return;
                        }

                        boolean keepAlive = isKeepAlive(request);
                        Response response = new Response(200, ContentType.TEXT_PLAIN);

                        RequestHandler handler = router.route(request);
                        if (handler == null) handler = router.getNotFoundHandler();

                        try {
                            Object result = handler.handle(request, response);
                            if (result instanceof Response r) response = r;
                            else if (result != null) response.setBody(result);
                        } catch (Exception ex) {
                            Object result = router.getExceptionHandler().handle(ex, request, response);
                            if (result instanceof Response r) response = r;
                            else if (result != null) response.setBody(result);
                        }

                        writeResponse(out, response, keepAlive);
                        request.drain();
                        if (!keepAlive) break;
                    }

                } catch (IOException e) {
                    if (!stopped) {
                        if (e instanceof java.net.SocketException)
                            log.debug("Connection closed: {}", e.getMessage());
                        else
                            log.error("I/O error handling request", e);
                    }
                } finally {
                    activeSockets.remove(socket);
                }
            });
        } catch (RejectedExecutionException ignored) {
            try { socket.close(); } catch (IOException e) { log.debug("Error closing socket on shutdown", e); }
        }
    }

    // ── WebSocket upgrade detection (zero-alloc) ──────────────────────────────

    private static boolean isWebSocketUpgrade(Request request) {
        ByteView upgrade = request.getRequestLine().getHeaders().view("Upgrade");
        if (upgrade == null) return false;
        if (!tokenEqualsIgnoreCase(upgrade, 0, upgrade.length(), "websocket")) return false;
        return connectionContainsUpgrade(request);
    }

    private static boolean connectionContainsUpgrade(Request request) {
        ByteView conn = request.getRequestLine().getHeaders().view("Connection");
        if (conn == null) return false;
        int len = conn.length(), i = 0;
        while (i < len) {
            while (i < len && conn.byteAt(i) == ' ') i++;
            int start = i;
            while (i < len && conn.byteAt(i) != ',') i++;
            if (tokenEqualsIgnoreCase(conn, start, i, "upgrade")) return true;
            i++;
        }
        return false;
    }

    private static boolean tokenEqualsIgnoreCase(ByteView view, int start, int end, String token) {
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

    // ── WebSocket handshake ───────────────────────────────────────────────────

    private void performHandshake(OutputStream out, Request request) throws IOException {
        ByteView keyView = request.getRequestLine().getHeaders().view("Sec-WebSocket-Key");
        if (keyView == null) throw new IOException("Missing Sec-WebSocket-Key header");

        MessageDigest sha1 = SHA1.get();
        sha1.reset();
        for (int i = 0, len = keyView.length(); i < len; i++) sha1.update(keyView.byteAt(i));
        sha1.update(WS_GUID_BYTES);

        byte[] accept = Base64.getEncoder().encode(sha1.digest());

        out.write(WS_HANDSHAKE_PREFIX);
        out.write(accept);
        out.write(WS_HANDSHAKE_SUFFIX);
        out.flush();
    }

    // ── WebSocket session loop ────────────────────────────────────────────────

    private void runWsLoop(WebSocketSession session, WebSocketHandler handler) {
        handler.onOpen(session);
        WebSocketFrame frame = new WebSocketFrame();
        try {
            while (session.isOpen()) {
                if (!session.readFrame(frame)) break;
                switch (frame.opcode()) {
                    case WebSocketFrame.OP_TEXT, WebSocketFrame.OP_BINARY
                            -> handler.onMessage(session, frame);
                    case WebSocketFrame.OP_CLOSE
                            -> session.closeFromPeer(frame);
                    case WebSocketFrame.OP_PING
                            -> session.sendPong(frame);
                    case WebSocketFrame.OP_PONG -> { /* heartbeat ack, no-op */ }
                }
            }
        } catch (IOException e) {
            handler.onError(session, e);
        } finally {
            handler.onClose(session, session.closeCode());
            session.forceClose();
        }
    }

    // ── HTTP keep-alive detection ─────────────────────────────────────────────

    private static boolean isKeepAlive(Request request) {
        if (request.headerEquals("Connection", "close")) return false;
        ByteView protocol = request.getRequestLine().getProtocol();
        int plen = protocol.length();
        if (plen == 8) {
            byte minor = protocol.byteAt(7);
            if (minor == '1') return true;
            if (minor == '0') return request.headerEquals("Connection", "keep-alive");
        }
        log.debug("Unrecognised protocol '{}', treating as close", protocol);
        return false;
    }

    // ── Response serialisation ────────────────────────────────────────────────

    private static void writeResponse(OutputStream out, Response response, boolean keepAlive) throws IOException {
        out.write(HTTP_1_1);
        byte[] statusBytes = response.getStatusBytes();
        if (statusBytes != null) out.write(statusBytes);
        else writeStatusPhrase(out, response.getStatusCode());
        out.write(CRLF);
        out.write(CONTENT_TYPE);
        out.write(response.getContentType());
        out.write(CRLF);
        response.writeHeaders(out);

        if (response.isStreaming()) {
            writeStreamingBody(out, response, keepAlive);
        } else {
            byte[] body = response.getBody();
            out.write(CONTENT_LENGTH);
            writeLong(out, body != null ? body.length : 0);
            out.write(CRLF);
            out.write(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            out.write(CRLF);
            if (body != null) out.write(body);
        }
        out.flush();
    }

    private static void writeStreamingBody(OutputStream out, Response response, boolean keepAlive) throws IOException {
        if (!response.isChunked()) {
            out.write(CONTENT_LENGTH);
            writeLong(out, response.getStreamLength());
            out.write(CRLF);
            out.write(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            out.write(CRLF);
            response.getStream().transferTo(out);
        } else {
            out.write(TRANSFER_CHUNKED);
            out.write(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
            out.write(CRLF);
            writeChunked(out, response.getStream());
        }
    }

    private static void writeStatusPhrase(OutputStream out, int statusCode) throws IOException {
        byte[] phrase = HttpStatus.bytesForCode(statusCode);
        if (phrase != null) out.write(phrase);
        else { writeLong(out, statusCode); out.write(UNKNOWN_STATUS_SUFFIX); }
    }

    private static void writeLong(OutputStream out, long value) throws IOException {
        if (value == 0) { out.write('0'); return; }
        byte[] buf = LONG_BUF.get();
        int pos = buf.length;
        boolean neg = value < 0;
        if (neg) value = -value;
        do { buf[--pos] = (byte) ('0' + value % 10); value /= 10; } while (value > 0);
        if (neg) buf[--pos] = '-';
        out.write(buf, pos, buf.length - pos);
    }

    private static void writeChunked(OutputStream out, InputStream stream) throws IOException {
        byte[] buf = new byte[8192];
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
        int shift   = 28;
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