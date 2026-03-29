package dev.relism.routing;

import dev.relism.http.ContentType;
import dev.relism.http.HttpMethod;
import dev.relism.fpr.core.ByteView;
import dev.relism.models.*;
import dev.relism.template.ErrorPages;

/**
 * Base router contract. Owns error handlers and the compile-time middleware
 * wrapping logic. The only concrete implementation is
 * {@link dev.relism.routing.routers.fastpathrouter.FastPathRouterImpl}.
 *
 * <p>All middleware composition happens once at boot time; the hot-path sees
 * only a plain {@link RequestHandler} call with zero allocation overhead.
 *
 * <p><b>Registration</b>: use {@link dev.relism.extension.FlashApp} — the single
 * public registration API. {@link #doRegister} is an infrastructure method.
 */
public abstract class AbstractRouter {

    protected SimpleHandler notFoundHandler = new SimpleHandler((req, res) -> {
        res.setStatusCode(404);
        res.setContentType(ContentType.TEXT_HTML);
        return ErrorPages.renderNotFound(req);
    });

    protected ExceptionHandler exceptionHandler = (ex, req, res) -> {
        res.setStatusCode(500);
        res.setContentType(ContentType.TEXT_HTML);
        return ErrorPages.renderException(req, ex);
    };

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
        return addRoute(method, PathUtils.sanitize(path), compile(handler, middlewares));
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

    public abstract RequestHandler route(Request request);

    protected abstract AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler);

    protected static void setPathParams(Request request, String[] names, ByteView source, int[] starts, int[] lens) {
        request.setPathParams(new PathParams(source, names, starts, lens));
    }

    @FunctionalInterface
    public interface ExceptionHandler {
        Object handle(Exception exception, Request request, Response response);
    }
}
