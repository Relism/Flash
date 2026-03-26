package dev.relism.extension;

import dev.relism.models.RequestHandler;
import dev.relism.routing.Middleware;

import java.util.List;

/**
 * Inspects a handler class at registration time and returns zero or more
 * {@link Middleware middlewares} to inject automatically.
 *
 * <p>Processors are called once per {@link FlashApp#register} call, before
 * the handler is compiled into the router. Returning an empty list is always
 * valid — processors may also use the call purely for side effects
 * (e.g. collecting OpenAPI metadata).
 *
 * <p>Register processors via {@link ExtensionContext#addAnnotationProcessor}.
 */
@FunctionalInterface
public interface AnnotationProcessor {
    List<Middleware> process(Class<? extends RequestHandler> handlerClass);
}
