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
    private static final byte[] CONNECTION_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_KEEPALIVE = "Connection: keep-alive\r\n".getBytes(StandardCharsets.UTF_8);

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

    /**
     * Registers a route on the internal router. The handler return value drives the response:
     * return a {@link dev.relism.models.Response} to replace it entirely, any other non-null
     * value to set it as the body, or {@code null} to leave the response object unchanged.
     */
    public HttpServer get(String path, SimpleHandler.FunctionalHandler h) {
        globalRouter.get(path, h);
        return this;
    }

    public HttpServer post(String path, SimpleHandler.FunctionalHandler h) {
        globalRouter.post(path, h);
        return this;
    }

    public HttpServer put(String path, SimpleHandler.FunctionalHandler h) {
        globalRouter.put(path, h);
        return this;
    }

    public HttpServer delete(String path, SimpleHandler.FunctionalHandler h) {
        globalRouter.delete(path, h);
        return this;
    }

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

                    Response response = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
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

                    request.getBody();

                    out.write(HTTP_1_1);
                    writeStatusPhrase(out, response.getStatusCode());
                    out.write(CRLF);
                    out.write(CONTENT_TYPE);
                    out.write(response.getContentType());
                    out.write(CRLF);
                    out.write(CONTENT_LENGTH);
                    writeInt(out, response.getBody() != null ? response.getBody().length : 0);
                    out.write(CRLF);
                    out.write(keepAlive ? CONNECTION_KEEPALIVE : CONNECTION_CLOSE);
                    out.write(CRLF);
                    if (response.getBody() != null)
                        out.write(response.getBody());
                    out.flush();

                    if (!keepAlive)
                        break;
                }
            } catch (IOException e) {
                if (!stopped)
                    log.error("I/O error handling request", e);
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

    private static void writeStatusPhrase(OutputStream out, int statusCode) throws IOException {
        byte[] phrase = HttpStatus.bytesForCode(statusCode);
        if (phrase != null) {
            out.write(phrase);
        } else {
            writeInt(out, statusCode);
            out.write(UNKNOWN_STATUS_SUFFIX);
        }
    }

    private static void writeInt(OutputStream out, int value) throws IOException {
        if (value == 0) {
            out.write(DIGITS[0]);
            return;
        }
        if (value < 0) {
            out.write('-');
            value = -value;
        }
        int divisor = 1;
        while (value / divisor >= 10)
            divisor *= 10;
        while (divisor > 0) {
            out.write(DIGITS[(value / divisor) % 10]);
            divisor /= 10;
        }
    }
}
