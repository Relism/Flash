package dev.relism;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.ContentType;
import dev.relism.http.HttpStatus;
import dev.relism.models.*;
import dev.relism.routing.AbstractRouter;
import dev.relism.routing.GlobalRouter;

import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Flash HTTP server. Owns a {@link GlobalRouter} with two routing tiers:
 * mounted sub-routers (matched by longest namespace prefix) and an internal
 * router as fallback. Error handlers are scoped to their respective router.
 */
@Slf4j
public class HttpServer {
    private final HttpServerConfiguration configuration;
    private final ServerSocket serverSocket;
    private final GlobalRouter globalRouter = new GlobalRouter();
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    private volatile boolean stopped = false;
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

    private static final byte[] HTTP_1_1 = "HTTP/1.1 ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CRLF = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE = "Content-Type: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_LENGTH = "Content-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_CLOSE    = "Connection: close\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_KEEPALIVE = "Connection: keep-alive\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRANSFER_CHUNKED    = "Transfer-Encoding: chunked\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINAL_CHUNK         = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

    private static final byte[] UNKNOWN_STATUS_SUFFIX = " Unknown".getBytes(StandardCharsets.UTF_8);

    private static final byte[][] DIGITS = new byte[10][1];
    static {
        for (int i = 0; i < 10; i++)
            DIGITS[i] = String.valueOf(i).getBytes(StandardCharsets.UTF_8);
    }

    public HttpServer(HttpServerConfiguration configuration) throws IOException {
        this.configuration = configuration;
        this.serverSocket = new ServerSocket(configuration.getPort());
    }

    /** Returns a future that completes once the accept loop is running and the server is ready. */
    public CompletableFuture<Void> start() {
        Thread.ofPlatform().name("flash-accept-loop").daemon(false).start(this::run);
        return readyFuture;
    }

    private void run() {
        try (executorService) {
            readyFuture.complete(null);
            while (!stopped) {
                Socket clientSocket = serverSocket.accept();
                process(clientSocket);
            }
        } catch (IOException e) {
            if (!stopped) {
                readyFuture.completeExceptionally(e);
                log.error("Accept loop error", e);
            }
        }
    }

    /** Closes all active connections and shuts down the executor. Returns when complete. */
    public CompletableFuture<Void> stop() {
        stopped = true;
        try {
            serverSocket.close();
        } catch (IOException e) {
            log.error("Error closing server socket", e);
        }
        activeSockets.forEach(s -> { try { s.close(); } catch (IOException ignored) {} });
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS))
                executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Mounts a sub-router under {@code namespace}. Requests whose path starts with the
     * namespace are dispatched to {@code router}; longest prefix wins.
     *
     * @throws dev.relism.exceptions.DuplicateNamespaceException if {@code namespace} is already mounted
     */
    public HttpServer mount(String namespace, AbstractRouter router) {
        globalRouter.mount(namespace, router);
        return this;
    }

    public HttpServer onNotFound(SimpleHandler.FunctionalHandler handler) {
        globalRouter.onNotFound(handler);
        return this;
    }

    public HttpServer onException(AbstractRouter.ExceptionHandler handler) {
        globalRouter.onException(handler);
        return this;
    }

    public HttpServer register(RequestHandler handler) {
        globalRouter.register(handler);
        return this;
    }

    public HttpServer get(String path, SimpleHandler.FunctionalHandler h)     { globalRouter.get(path, h);     return this; }
    public HttpServer post(String path, SimpleHandler.FunctionalHandler h)    { globalRouter.post(path, h);    return this; }
    public HttpServer put(String path, SimpleHandler.FunctionalHandler h)     { globalRouter.put(path, h);     return this; }
    public HttpServer delete(String path, SimpleHandler.FunctionalHandler h)  { globalRouter.delete(path, h);  return this; }
    public HttpServer patch(String path, SimpleHandler.FunctionalHandler h)   { globalRouter.patch(path, h);   return this; }
    public HttpServer options(String path, SimpleHandler.FunctionalHandler h) { globalRouter.options(path, h); return this; }
    public HttpServer head(String path, SimpleHandler.FunctionalHandler h)    { globalRouter.head(path, h);    return this; }
    public HttpServer trace(String path, SimpleHandler.FunctionalHandler h)   { globalRouter.trace(path, h);   return this; }
    public HttpServer connect(String path, SimpleHandler.FunctionalHandler h) { globalRouter.connect(path, h); return this; }
    public HttpServer purge(String path, SimpleHandler.FunctionalHandler h)   { globalRouter.purge(path, h);   return this; }


    private void process(Socket socket) {
        activeSockets.add(socket);
        executorService.submit(() -> {
            try (socket;
                    InputStream in = socket.getInputStream();
                    OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {
                RequestParser parser = new RequestParser(configuration.getMaxHeaderBufferSize());
                while (!stopped) {
                    Request request = parser.parse(in);
                    if (request == null)
                        break;

                    boolean keepAlive = isKeepAlive(request);

                    Response response = new Response(200, ContentType.TEXT_PLAIN);
                    RequestHandler handler = globalRouter.route(request);

                    try {
                        Object result = handler.handle(request, response);
                        if (result instanceof Response r)
                            response = r;
                        else if (result != null)
                            response.setBody(result);
                    } catch (Exception ex) {
                        Object result = globalRouter.resolveExceptionHandler(request).handle(ex, request, response);
                        if (result instanceof Response r)
                            response = r;
                        else if (result != null)
                            response.setBody(result);
                    }

                    writeResponse(out, response, keepAlive);
                    request.drain();

                    if (!keepAlive)
                        break;
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
    }

    private static boolean isKeepAlive(Request request) {
        if (request.headerEquals("Connection", "close")) return false;
        // Detect HTTP version from last byte of protocol field: "HTTP/1.1" → '1', "HTTP/1.0" → '0'
        ByteView protocol = request.getRequestLine().getProtocol();
        return protocol.length() == 8 && protocol.byteAt(7) == '1'
                || request.headerEquals("Connection", "keep-alive");
    }

    /**
     * Writes a complete HTTP response. The fixed-body path (the common case for simple handlers
     * like /plaintext) is kept inline; streaming and chunked bodies are delegated to
     * {@link #writeStreamingBody} so the JIT can optimise this method aggressively.
     */
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

    /** Streaming write path — extracted from {@link #writeResponse} to keep the hot method small. */
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
        if (value == 0) { out.write(DIGITS[0]); return; }
        if (value < 0)  { out.write('-'); value = -value; }
        long divisor = 1;
        while (value / divisor >= 10) divisor *= 10;
        while (divisor > 0) { out.write(DIGITS[(int) ((value / divisor) % 10)]); divisor /= 10; }
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
