package dev.relism.routing;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.ContentType;
import dev.relism.http.HttpMethod;
import dev.relism.models.*;
import dev.relism.template.ErrorPages;

import lombok.AccessLevel;
import lombok.Getter;

import java.nio.charset.StandardCharsets;

/**
 * Base router. Each router has a namespace prefix (default {@code "/"}).
 * Error handlers are scoped to this router.
 *
 * <p>Router-level {@link Middleware middlewares} are passed at construction time and
 * pre-fused into a single wrapper applied to every handler registered on this router.
 * Handler-level middlewares are passed to {@link #doRegister} and wrapped
 * <em>inside</em> the router-level chain.
 *
 * <p>All middleware composition happens once at boot time; the hot-path sees only a plain
 * {@link RequestHandler} call with zero allocation and zero lookup overhead.
 *
 * <p><b>Registration</b>: use {@link dev.relism.extension.FlashApp} or
 * {@link dev.relism.extension.FlashScope} — they are the public registration API.
 * {@link #doRegister} is an infrastructure method for those entry points.
 */
public abstract class AbstractRouter {

    @Getter
    protected String namespace = "/";

    @Getter(AccessLevel.PACKAGE)
    protected byte[] namespaceBytes = new byte[]{ '/' };

    /**
     * Pre-fused router-level middleware, or {@code null} when none were registered.
     * A single null-check in {@link #compile} is the only cost when no router middleware exists.
     */
    private final Middleware   routerMiddleware;

    /**
     * Raw router-level middleware array, kept for route event emission by
     * {@link dev.relism.extension.FlashApp} and {@link dev.relism.extension.FlashScope}.
     * Never mutated after construction. Not used on the hot-path.
     */
    private final Middleware[] rawRouterMiddlewares;

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

    /**
     * Constructs a router with optional router-level middlewares.
     * Middlewares are pre-fused at construction time; the first element executes outermost.
     *
     * @param middlewares zero or more middlewares applied to every handler on this router
     */
    protected AbstractRouter(Middleware... middlewares) {
        this.rawRouterMiddlewares = middlewares;
        this.routerMiddleware = middlewares.length == 0 ? null
                              : middlewares.length == 1 ? middlewares[0]
                              : Middleware.of(middlewares);
    }

    /**
     * Returns the raw router-level middleware array as passed at construction.
     * Used by {@link dev.relism.extension.FlashApp} and {@link dev.relism.extension.FlashScope}
     * to populate {@link dev.relism.extension.RouteEvent#middlewareChain()}.
     * Never mutated; never called on the hot-path.
     */
    public Middleware[] routerMiddlewares() { return rawRouterMiddlewares; }

    // ── Internal wiring ───────────────────────────────────────────────────────

    SimpleHandler    getNotFoundHandler()  { return notFoundHandler; }
    ExceptionHandler getExceptionHandler() { return exceptionHandler; }

    void setNamespace(String namespace) {
        this.namespace = namespace;
        this.namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Compiles a handler with its middleware chain. Called once per handler at registration.
     *
     * <p>Application order (innermost → outermost):
     * <ol>
     *   <li>handler-level middlewares (passed at the call site)</li>
     *   <li>router-level middleware (set in the constructor)</li>
     * </ol>
     */
    private RequestHandler compile(RequestHandler handler, Middleware[] handlerMiddlewares) {
        RequestHandler compiled = handler;
        for (int i = handlerMiddlewares.length - 1; i >= 0; i--)
            compiled = new SimpleHandler(handlerMiddlewares[i].wrap(compiled));
        if (routerMiddleware != null)
            compiled = new SimpleHandler(routerMiddleware.wrap(compiled));
        return compiled;
    }

    // ── Error handler configuration ───────────────────────────────────────────

    public AbstractRouter onNotFound(SimpleHandler.FunctionalHandler handler) {
        this.notFoundHandler = new SimpleHandler(handler);
        return this;
    }

    public AbstractRouter onException(ExceptionHandler handler) {
        this.exceptionHandler = handler;
        return this;
    }

    // ── Infrastructure registration ───────────────────────────────────────────
    // Used by FlashApp and FlashScope. Not part of the public user-facing API.

    /**
     * Registers a lambda handler immediately with a pre-built middleware array.
     * Infrastructure method — use {@link dev.relism.extension.FlashApp} instead.
     */
    public AbstractRouter doRegister(HttpMethod method, String path,
                                     SimpleHandler.FunctionalHandler handler, Middleware[] middlewares) {
        return addRoute(method, PathUtils.sanitize(path),
                compile(new SimpleHandler(handler), middlewares));
    }

    /**
     * Registers a class-based handler immediately with a pre-built middleware array.
     * Reads {@link Route @Route} for method and path.
     * Infrastructure method — use {@link dev.relism.extension.FlashApp} instead.
     */
    public AbstractRouter doRegister(RequestHandler handler, Middleware[] middlewares) {
        Route annotation = handler.getClass().getAnnotation(Route.class);
        if (annotation != null)
            addRoute(annotation.method(), annotation.path(),
                    compile(handler, middlewares));
        return this;
    }

    /**
     * Registers a class-based handler with an explicit method and path (ignoring the
     * {@link Route @Route} annotation's path). Used by {@link dev.relism.extension.FlashScope}
     * to prepend the scope's namespace prefix.
     * Infrastructure method — use {@link dev.relism.extension.FlashScope} instead.
     */
    public AbstractRouter doRegister(HttpMethod method, String path,
                                     RequestHandler handler, Middleware[] middlewares) {
        return addRoute(method, PathUtils.sanitize(path), compile(handler, middlewares));
    }

    // ── Routing ───────────────────────────────────────────────────────────────

    public abstract RequestHandler route(Request request);

    /**
     * Stores a pre-compiled handler in the underlying routing structure.
     * The handler passed here is already fully wrapped — implementations must not
     * apply any additional middleware logic.
     */
    protected abstract AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler);

    protected static void setPathParams(Request request, String[] names, ByteView source, int[] starts, int[] lens) {
        request.setPathParams(new PathParams(source, names, starts, lens));
    }

    @FunctionalInterface
    public interface ExceptionHandler {
        Object handle(Exception exception, Request request, Response response);
    }
}
