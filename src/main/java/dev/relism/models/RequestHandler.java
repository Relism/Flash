package dev.relism.models;

/**
 * Base class for class-based route handlers.
 *
 * <p>Annotate the subclass with {@link dev.relism.routing.Route @Route} and register it
 * via {@link dev.relism.routing.AbstractRouter#register}. For one-off routes, prefer the
 * lambda DSL ({@code server.get(path, handler)}) which wraps a {@link SimpleHandler} internally.
 */
public abstract class RequestHandler {
    /**
     * Handles an incoming request. The return value determines the response body:
     * return a {@link Response} to replace the whole response, any other non-null value
     * to set it as the body, or {@code null} to leave the response as-is.
     */
    public abstract Object handle(Request request, Response response);
}