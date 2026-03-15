package dev.relism;

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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the Flash HTTP server.
 *
 * <p>Internally, the server owns a {@link GlobalRouter} with two tiers of routing:
 * <ol>
 *   <li><b>Mounted sub-routers</b> — registered via {@link #mount}. Each owns a namespace
 *       prefix (e.g. {@code /api}) and handles all paths under it. Matched by longest prefix first.</li>
 *   <li><b>Internal router</b> — the fallback used when no sub-router claims the path.
 *       Routes registered directly on the server ({@link #get}, {@link #post}, etc.) go here.</li>
 * </ol>
 *
 * <p>Error handlers ({@link #onNotFound}, {@link #onException}) registered on the server
 * apply only to the internal router. Each mounted sub-router has its own independent handlers.
 */
@Slf4j
public class HttpServer {
    private final HttpServerConfiguration configuration;
    private final ServerSocket serverSocket;
    private final GlobalRouter globalRouter = new GlobalRouter();
    private final ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    private volatile boolean             stopped     = false;
    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();

    private static final byte[] HTTP_1_1        = "HTTP/1.1 ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CRLF            = "\r\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_TYPE    = "Content-Type: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONTENT_LENGTH  = "Content-Length: ".getBytes(StandardCharsets.UTF_8);
    private static final byte[] CONNECTION_CLOSE = "Connection: close\r\n".getBytes(StandardCharsets.UTF_8);

    private static final byte[] UNKNOWN_STATUS_SUFFIX = " Unknown".getBytes(StandardCharsets.UTF_8);

    private static final byte[][] DIGITS = new byte[10][1];
    static {
        for (int i = 0; i < 10; i++) DIGITS[i] = String.valueOf(i).getBytes(StandardCharsets.UTF_8);
    }

    public HttpServer(HttpServerConfiguration configuration) throws IOException {
        this.configuration = configuration;
        this.serverSocket = new ServerSocket(configuration.getPort());
    }

    /**
     * Starts the server on a new non-daemon virtual thread and returns a future that completes
     * when the accept loop is running and the server is ready to serve requests.
     */
    public CompletableFuture<Void> start() {
        Thread.ofVirtual().name("flash-accept-loop").start(this::run);
        return readyFuture;
    }

    private void run() {
        log.info("Server started on port {}", configuration.getPort());
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

    /**
     * Stops the server and waits for in-flight requests to complete.
     * The returned future is already completed when this method returns.
     */
    public CompletableFuture<Void> stop() {
        stopped = true;
        try { serverSocket.close(); } catch (IOException e) { log.error("Error closing server socket", e); }
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) executorService.shutdownNow();
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }
        return CompletableFuture.completedFuture(null);
    }

    // --- Routing ---

    /**
     * Mounts a sub-router under the given namespace prefix.
     *
     * <p>Any request whose path starts with {@code namespace} will be dispatched to {@code router}
     * instead of the internal router. When multiple namespaces match, the longest prefix wins.
     * The router's own {@code onNotFound} and {@code onException} handlers are used for its paths —
     * the server-level handlers do not apply.
     *
     * @throws dev.relism.exceptions.DuplicateNamespaceException if {@code namespace} is already mounted
     */
    public HttpServer mount(String namespace, AbstractRouter router) {
        globalRouter.mount(namespace, router);
        return this;
    }

    // --- Global error handlers ---

    /**
     * Sets the 404 handler for routes registered directly on this server.
     * Does not affect mounted sub-routers, which each carry their own not-found handler.
     */
    public HttpServer onNotFound(SimpleHandler.FunctionalHandler handler) {
        globalRouter.onNotFound(handler);
        return this;
    }

    /**
     * Sets the exception handler for routes registered directly on this server.
     * Does not affect mounted sub-routers, which each carry their own exception handler.
     */
    public HttpServer onException(AbstractRouter.ExceptionHandler handler) {
        globalRouter.onException(handler);
        return this;
    }

    // --- DSL ---

    /**
     * Registers an annotation-based handler on the internal router.
     * The handler's class must carry a {@link dev.relism.routing.Route @Route} annotation
     * declaring the HTTP method and path. To register on a specific sub-router, call
     * {@link AbstractRouter#register} on that router directly.
     */
    public HttpServer register(RequestHandler handler) { globalRouter.register(handler); return this; }

    /**
     * Registers a route on the internal router (not on any mounted sub-router).
     * The handler's return value is used as the response body; returning a {@link dev.relism.models.Response}
     * instance replaces the entire response object.
     */
    public HttpServer get(String path, SimpleHandler.FunctionalHandler h)    { globalRouter.get(path, h);    return this; }
    /** @see #get(String, SimpleHandler.FunctionalHandler) */
    public HttpServer post(String path, SimpleHandler.FunctionalHandler h)   { globalRouter.post(path, h);   return this; }
    /** @see #get(String, SimpleHandler.FunctionalHandler) */
    public HttpServer put(String path, SimpleHandler.FunctionalHandler h)    { globalRouter.put(path, h);    return this; }
    /** @see #get(String, SimpleHandler.FunctionalHandler) */
    public HttpServer delete(String path, SimpleHandler.FunctionalHandler h) { globalRouter.delete(path, h); return this; }

    // --- Request processing ---

    private void process(Socket socket) {
        executorService.submit(() -> {
            try (socket;
                 InputStream in = socket.getInputStream();
                 OutputStream out = new BufferedOutputStream(socket.getOutputStream())
            ) {
                long start = System.nanoTime();
                Request request = RequestParser.parse(in);
                if (request == null) return;

                Response response = new Response(200, new byte[0], ContentType.TEXT_PLAIN);
                RequestHandler handler = globalRouter.route(request);

                try {
                    Object result = handler.handle(request, response);
                    if (result instanceof Response r) response = r;
                    else if (result != null) response.setBody(result);
                } catch (Exception ex) {
                    Object result = globalRouter.resolveExceptionHandler(request).handle(ex, request, response);
                    if (result instanceof Response r) response = r;
                    else if (result != null) response.setBody(result);
                }

                out.write(HTTP_1_1);
                writeStatusPhrase(out, response.getStatusCode());
                out.write(CRLF);
                out.write(CONTENT_TYPE);
                out.write(response.getContentType());
                out.write(CRLF);
                out.write(CONTENT_LENGTH);
                writeInt(out, response.getBody() != null ? response.getBody().length : 0);
                out.write(CRLF);
                out.write(CONNECTION_CLOSE);
                out.write(CRLF);
                if (response.getBody() != null) out.write(response.getBody());
                out.flush();
                log.info("{} ms: {}", (System.nanoTime() - start) / 1_000_000.0, request.getRequestLine());

            } catch (IOException e) {
                log.error("I/O error handling request", e);
            }
        });
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
        if (value == 0) { out.write(DIGITS[0]); return; }
        if (value < 0)  { out.write('-'); value = -value; }
        int divisor = 1;
        while (value / divisor >= 10) divisor *= 10;
        while (divisor > 0) { out.write(DIGITS[(value / divisor) % 10]); divisor /= 10; }
    }
}
