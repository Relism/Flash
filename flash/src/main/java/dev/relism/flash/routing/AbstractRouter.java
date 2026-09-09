package dev.relism.flash.routing;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.models.*;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathRouterImpl;
import dev.relism.flash.Flash;
import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.template.ErrorPages;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * Base router contract. Owns error handlers and the compile-time middleware
 * wrapping logic. The only concrete implementation is
 * {@link FastPathRouterImpl}.
 *
 * <p>All middleware composition happens once at boot time; the hot-path sees
 * only a plain {@link RequestHandler} call with zero allocation overhead.
 *
 * <h3>Default error handlers</h3>
 * <ul>
 *   <li><b>Dev mode</b> ({@code -Dflash.env=dev}): rich HTML pages with full stack traces.</li>
 *   <li><b>Prod mode</b> (default): generic JSON — no internal detail leaked to clients.</li>
 * </ul>
 * Override via {@link FlashApp#onNotFound} /
 * {@link FlashApp#onException}.
 *
 * <p><b>Registration</b>: use {@link FlashApp} — the single
 * public registration API. {@link #doRegister} is an infrastructure method.
 */
@Slf4j
public abstract class AbstractRouter {

    /** Eagerly validates and compiles this route graph before traffic is accepted. */
    public void compile() {}

    // Pre-encoded prod JSON error bodies — zero allocation on error paths.
    private static final byte[] JSON_404 = "{\"error\":\"Not Found\",\"status\":404}"
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] JSON_500 = "{\"error\":\"Internal Server Error\",\"status\":500}"
            .getBytes(StandardCharsets.UTF_8);

    protected SimpleHandler notFoundHandler = Flash.DEV
            ? new SimpleHandler((req, res) -> {
                res.status(404);
                res.type(ContentType.TEXT_HTML);
                return ErrorPages.renderNotFound(req);
            })
            : new SimpleHandler((req, res) -> {
                res.status(404);
                res.type(ContentType.JSON);
                return JSON_404;
            });

    protected ExceptionHandler exceptionHandler = Flash.DEV
            ? (ex, req, res) -> {
                if (ex instanceof HttpException http) return renderHttpException(http, res);
                res.status(500);
                res.type(ContentType.TEXT_HTML);
                return ErrorPages.renderException(req, ex);
            }
            : (ex, req, res) -> {
                if (ex instanceof HttpException http) return renderHttpException(http, res);
                log.error("Unhandled exception in {} {}", req.method(), req.path(), ex);
                res.status(500);
                res.type(ContentType.JSON);
                return JSON_500;
            };

    /**
     * {@link HttpException} carries the status the caller meant; without this it reached the
     * catch-all above and every one of them came back as 500 — including the 400s
     * {@code RequestHelper} and {@code flash-ext-jackson} raise for malformed input.
     *
     * <p>Deliberately not pre-encoded like {@link #JSON_404}: the message is per-exception, and
     * an error path that already unwound a stack does not need the allocation shaved.
     */
    private static byte[] renderHttpException(HttpException failure, Response res) {
        res.status(failure.status());
        res.type(ContentType.JSON);
        String message = failure.getMessage();
        StringBuilder out = new StringBuilder(48 + (message == null ? 0 : message.length()));
        out.append("{\"error\":\"");
        escapeJson(message == null ? "" : message, out);
        out.append("\",\"status\":").append(failure.status()).append('}');
        return out.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Minimal RFC 8259 string escaping — enough for an exception message. */
    private static void escapeJson(String text, StringBuilder out) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default   -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
    }

    public SimpleHandler    getNotFoundHandler()  { return notFoundHandler; }
    public ExceptionHandler getExceptionHandler() { return exceptionHandler; }

    public void onNotFound(SimpleHandler.FunctionalHandler handler) {
        this.notFoundHandler = new SimpleHandler(handler);
    }

    public void onException(ExceptionHandler handler) {
        this.exceptionHandler = handler;
    }

    // ── Infrastructure registration ──────────────────────────────────────────
    // Used by FlashApp at compile time. Not part of the user-facing API.

    /**
     * Registers a handler with a pre-built middleware array.
     * Compiles the middleware chain once at boot — zero overhead on hot-path.
     */
    public AbstractRouter doRegister(HttpMethod method, String path,
                                     RequestHandler handler, Middleware[] middlewares) {
        String target = method == HttpMethod.CONNECT
                ? PathUtils.sanitizeAuthority(path)
                : PathUtils.sanitize(path);
        return addRoute(method, target, compile(handler, middlewares));
    }

    /**
     * Compiles handler-level middleware into a single wrapped handler.
     * Called once per route at registration time.
     */
    private RequestHandler compile(RequestHandler handler, Middleware[] middlewares) {
        RequestHandler compiled = handler;
        for (int i = middlewares.length - 1; i >= 0; i--)
            compiled = new SimpleHandler(middlewares[i].wrap(compiled));
        return compiled;
    }

    // ── Routing ──────────────────────────────────────────────────────────────

    /**
     * Creates a fresh per-connection scratch object for {@link #route}, or {@code null} if this
     * router implementation keeps no reusable per-connection state. Called once per connection
     * by the connection driver (e.g. {@code Http1Connection}), which holds the opaque result and
     * passes it back into every {@link #route} call for that connection's whole lifetime — the
     * same "create once per connection, reuse across requests" shape already used there for
     * {@code RequestParser}.
     *
     * thread", which under this codebase's one-virtual-thread-per-connection model is "one per
     * connection with no upper bound and no pooling" — exactly the failure mode
     * {@code ConnectionScratch} already exists to avoid for every other per-connection buffer.
     * An explicit, caller-owned scratch object achieves the same per-connection reuse without
     * that unbounded-growth risk, and without requiring {@code routing} to depend on
     * {@code transport}'s {@code ConnectionScratch} type (this package has no such dependency
     * than extending {@code ConnectionScratch} itself, which is what an earlier draft of this
     * fix assumed).
     */
    public Object newScratch() {
        return null;
    }

    public abstract RequestHandler route(Request request, Object scratch);

    protected abstract AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler);

    @FunctionalInterface
    public interface ExceptionHandler {
        Object handle(Exception exception, Request request, Response response);
    }
}
