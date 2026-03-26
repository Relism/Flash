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
 * Handler-level middlewares are passed to the {@link #register} / {@link #get} family of
 * methods and wrapped <em>inside</em> the router-level chain.
 *
 * <p>All middleware composition happens once at boot time; the hot-path sees only a plain
 * {@link RequestHandler} call with zero allocation and zero lookup overhead.
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
    private final Middleware routerMiddleware;

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
        this.routerMiddleware = middlewares.length == 0 ? null
                              : middlewares.length == 1 ? middlewares[0]
                              : Middleware.of(middlewares);
    }

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
        // handler-level: wrap from innermost outward; box FunctionalHandler → SimpleHandler once per layer
        for (int i = handlerMiddlewares.length - 1; i >= 0; i--)
            compiled = new SimpleHandler(handlerMiddlewares[i].wrap(compiled));
        // router-level: outermost wrapper, executes first
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

    // ── Internal registration (package-private) ───────────────────────────────

    /**
     * Registers a lambda handler immediately with a pre-built middleware array.
     * Called by {@link RouteHandle#with} and by {@link dev.relism.extension.FlashApp}.
     */
    public AbstractRouter doRegister(HttpMethod method, String path,
                                     SimpleHandler.FunctionalHandler handler, Middleware[] middlewares) {
        return addRoute(method, PathUtils.sanitize(path),
                compile(new SimpleHandler(handler), middlewares));
    }

    /**
     * Registers a class-based handler immediately with a pre-built middleware array.
     * Called by {@link RouteHandle#with} and by {@link dev.relism.extension.FlashApp}.
     */
    public AbstractRouter doRegister(RequestHandler handler, Middleware[] middlewares) {
        Route annotation = handler.getClass().getAnnotation(Route.class);
        if (annotation != null)
            addRoute(annotation.method(), annotation.path(),
                    compile(handler, middlewares));
        return this;
    }

    // ── Lambda handler registration ───────────────────────────────────────────

    /**
     * Begins registration of a lambda handler. Call {@link RouteHandle#with} on the
     * returned handle to supply middlewares (if any) and complete the registration.
     *
     * <pre>{@code
     * router.get("/ping",  (req, res) -> "pong").with();
     * router.get("/admin", adminHandler).with(auth, logging);
     * }</pre>
     */
    public RouteHandle<AbstractRouter> get    (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.GET,     path, h, m)); }
    public RouteHandle<AbstractRouter> post   (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.POST,    path, h, m)); }
    public RouteHandle<AbstractRouter> put    (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.PUT,     path, h, m)); }
    public RouteHandle<AbstractRouter> delete (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.DELETE,  path, h, m)); }
    public RouteHandle<AbstractRouter> patch  (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.PATCH,   path, h, m)); }
    public RouteHandle<AbstractRouter> options(String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.OPTIONS, path, h, m)); }
    public RouteHandle<AbstractRouter> head   (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.HEAD,    path, h, m)); }
    public RouteHandle<AbstractRouter> trace  (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.TRACE,   path, h, m)); }
    public RouteHandle<AbstractRouter> connect(String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.CONNECT, path, h, m)); }
    public RouteHandle<AbstractRouter> purge  (String path, SimpleHandler.FunctionalHandler h) { return new RouteHandle<>(this, m -> doRegister(HttpMethod.PURGE,   path, h, m)); }

    // ── Class-based handler registration ─────────────────────────────────────

    /**
     * Begins registration of a class-based handler. The class must carry a
     * {@link Route @Route} annotation. Call {@link RouteHandle#with} to supply
     * optional extra middlewares and complete the registration.
     *
     * <pre>{@code
     * router.register(new BlogHandler()).with();
     * router.register(new AdminHandler()).with(logging);
     * }</pre>
     */
    public RouteHandle<AbstractRouter> register(RequestHandler handler) {
        return new RouteHandle<>(this, m -> doRegister(handler, m));
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
