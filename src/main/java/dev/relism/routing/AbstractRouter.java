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
 * Base class for all routers, used both for the server's internal router and for
 * mounted sub-routers.
 *
 * <p>Each router has a <b>namespace</b> (e.g. {@code /api}). Routes added via
 * {@link #get}, {@link #post}, etc. are <em>relative</em> to the namespace; the
 * implementation prepends it when registering. The namespace is {@code "/"} by default
 * and is set automatically by {@link GlobalRouter#mount}.
 *
 * <p>Error handlers ({@link #onNotFound}, {@link #onException}) are scoped to this router.
 * When a sub-router is mounted on the server, its handlers take precedence over the
 * server-level ones for all paths under its namespace.
 */
public abstract class AbstractRouter {

    @Getter
    protected String namespace = "/";

    @Getter(AccessLevel.PACKAGE)
    protected byte[] namespaceBytes = new byte[]{ '/' };

    // --- Default handlers ---

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

    // Package-private — used by GlobalRouter only
    SimpleHandler    getNotFoundHandler()  { return notFoundHandler; }
    ExceptionHandler getExceptionHandler() { return exceptionHandler; }
    void setNamespace(String namespace) {
        this.namespace = namespace;
        this.namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
    }

    // --- Public API ---

    /** Overrides the default 404 response for unmatched paths under this router's namespace. */
    public AbstractRouter onNotFound(SimpleHandler.FunctionalHandler handler) {
        this.notFoundHandler = new SimpleHandler(handler);
        return this;
    }

    /** Overrides the default 500 response for uncaught exceptions thrown by handlers under this router. */
    public AbstractRouter onException(ExceptionHandler handler) {
        this.exceptionHandler = handler;
        return this;
    }

    /**
     * Registers a route relative to this router's namespace.
     *
     * <p>The handler's return value drives the response:
     * <ul>
     *   <li>Return a {@link Response} to replace the entire response object.</li>
     *   <li>Return any other non-null value to use it as the body (via {@code toString()} or raw bytes).</li>
     *   <li>Return {@code null} to leave the response unchanged from what was set on the {@code Response} parameter.</li>
     * </ul>
     */
    public AbstractRouter get(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.GET, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    /** @see #get(String, SimpleHandler.FunctionalHandler) */
    public AbstractRouter post(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.POST, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    /** @see #get(String, SimpleHandler.FunctionalHandler) */
    public AbstractRouter put(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.PUT, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    /** @see #get(String, SimpleHandler.FunctionalHandler) */
    public AbstractRouter delete(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.DELETE, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    /**
     * Registers a class-based handler. The handler's class must be annotated with
     * {@link Route @Route} declaring the HTTP method and path (relative to this router's namespace).
     * If the annotation is absent, the call is silently ignored.
     */
    public AbstractRouter register(RequestHandler handler) {
        Route annotation = handler.getClass().getAnnotation(Route.class);
        if (annotation != null) {
            addRoute(HttpMethod.valueOf(annotation.method()), annotation.path(), handler);
        }
        return this;
    }

    // --- For implementors ---

    public abstract RequestHandler route(Request request);

    protected abstract AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler);

    /**
     * Sets the path parameters captured during routing.
     * Implementations must call this whenever the matched route contains path parameters.
     * Centralised here so all router implementations participate in the same contract
     * and produce a consistent {@link PathParams} regardless of the matching strategy.
     */
    protected static void setPathParams(Request request, String[] names, ByteView source, int[] starts, int[] lens) {
        request.setPathParams(new PathParams(source, names, starts, lens));
    }

    @FunctionalInterface
    public interface ExceptionHandler {
        Object handle(Exception exception, Request request, Response response);
    }
}
