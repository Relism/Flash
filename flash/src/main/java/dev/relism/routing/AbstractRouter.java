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
 * Base router. Each router has a namespace prefix (default {@code "/"}); routes added via
 * {@link #get}, {@link #post}, etc. are relative to it. Error handlers are scoped to this router.
 * The namespace is set automatically by {@link GlobalRouter#mount}.
 */
public abstract class AbstractRouter {

    @Getter
    protected String namespace = "/";

    @Getter(AccessLevel.PACKAGE)
    protected byte[] namespaceBytes = new byte[]{ '/' };

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

    SimpleHandler    getNotFoundHandler()  { return notFoundHandler; }
    ExceptionHandler getExceptionHandler() { return exceptionHandler; }
    void setNamespace(String namespace) {
        this.namespace = namespace;
        this.namespaceBytes = namespace.getBytes(StandardCharsets.UTF_8);
    }

    public AbstractRouter onNotFound(SimpleHandler.FunctionalHandler handler) {
        this.notFoundHandler = new SimpleHandler(handler);
        return this;
    }

    public AbstractRouter onException(ExceptionHandler handler) {
        this.exceptionHandler = handler;
        return this;
    }

    /**
     * Registers a route relative to this router's namespace. Return a {@link Response} to replace
     * it entirely, any other non-null value to set it as the body, or {@code null} to leave it unchanged.
     */
    public AbstractRouter get(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.GET, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    public AbstractRouter post(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.POST, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    public AbstractRouter put(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.PUT, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    public AbstractRouter delete(String path, SimpleHandler.FunctionalHandler handler) {
        return addRoute(HttpMethod.DELETE, PathUtils.sanitize(path), new SimpleHandler(handler));
    }

    /** Registers a class-based handler; the class must be annotated with {@link Route @Route}. */
    public AbstractRouter register(RequestHandler handler) {
        Route annotation = handler.getClass().getAnnotation(Route.class);
        if (annotation != null) {
            addRoute(HttpMethod.valueOf(annotation.method()), annotation.path(), handler);
        }
        return this;
    }

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
