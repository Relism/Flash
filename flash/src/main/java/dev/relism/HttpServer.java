package dev.relism;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.ContentType;
import dev.relism.http.HttpStatus;
import dev.relism.models.*;
import dev.relism.extension.FlashConfiguration;
import dev.relism.routing.AbstractRouter;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Pure I/O transport layer. Owns the {@link ServerSocket}, the virtual-thread
 * executor, and the keep-alive accept loop. Routing is delegated to a single
 * {@link AbstractRouter}.
 *
 * <p>Package-private : use {@link dev.relism.extension.FlashApp} as the single
 * entry point.
 */
@Slf4j
class HttpServer implements ServerHandle {

    private final FlashConfiguration configuration;
    private final ServerSocket       serverSocket;
    private final AbstractRouter     router;
    private final ExecutorService    executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket>        activeSockets   = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped = false;
    private final AtomicReference<Thread> acceptThread = new AtomicReference<>();

    private static final byte[] HTTP_1_1             = "HTTP/1.1 ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CRLF                 = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE         = "Content-Type: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_LENGTH       = "Content-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_CLOSE     = "Connection: close\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_KEEPALIVE = "Connection: keep-alive\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_CHUNKED     = "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINAL_CHUNK          = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] UNKNOWN_STATUS_SUFFIX = " Unknown".getBytes(StandardCharsets.UTF_8);

    // Per-thread scratch buffers — allocated once per VT, reused for every request.
    private static final ThreadLocal<byte[]> LONG_BUF  = ThreadLocal.withInitial(() -> new byte[20]);
    private static final ThreadLocal<byte[]> CHUNK_BUF = ThreadLocal.withInitial(() -> new byte[8192]);

    HttpServer(FlashConfiguration configuration, AbstractRouter router) throws IOException {
        this.configuration = configuration;
        this.serverSocket  = new ServerSocket(configuration.getPort());
        this.router        = router;
    }

    @Override
    public void start() {
        acceptThread.set(Thread.ofPlatform().name("flash-accept-loop").daemon(false).start(this::run));
    }

    @Override
    public void startAndBlock() {
        start();
        try { acceptThread.get().join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private void run() {
        while (!stopped) {
            try {
                process(serverSocket.accept());
            } catch (IOException e) {
                if (!stopped) log.error("Accept loop error", e);
            }
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

    // ── Hot-path ─────────────────────────────────────────────────────────────

    private void process(Socket socket) {
        try {
            executorService.submit(() -> {
                activeSockets.add(socket);
                try (socket;
                        InputStream  in  = socket.getInputStream();
                        OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
                    RequestParser parser = new RequestParser(
                            configuration.getMaxHeaderBufferSize(),
                            (InetSocketAddress) socket.getRemoteSocketAddress());
                    while (!stopped) {
                        Request request = parser.parse(in);
                        if (request == null) break;

                        boolean  keepAlive = isKeepAlive(request);
                        Response response  = new Response(200, ContentType.TEXT_PLAIN);

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
            // Executor already shut down — close the socket so the client isn't left hanging.
            try { socket.close(); } catch (IOException e) { log.debug("Error closing socket on shutdown", e); }
        }
    }

    private static boolean isKeepAlive(Request request) {
        if (request.headerEquals("Connection", "close")) return false;
        ByteView protocol = request.getRequestLine().getProtocol();
        return protocol.length() == 8 && protocol.byteAt(7) == '1'
                || request.headerEquals("Connection", "keep-alive");
    }

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
        byte[] buf = CHUNK_BUF.get();
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
            if (digit != 0 || !leading) { leading = false; out.write(digit < 10 ? '0' + digit : 'a' + digit - 10); }
            shift -= 4;
        }
        if (leading) out.write('0');
    }
}
