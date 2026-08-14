package dev.relism.flash.extension;

import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.routing.MiddlewareNode;

import java.util.List;

/**
 * Inspects a handler class at registration time and returns zero or more
 * {@link MiddlewareNode middleware nodes} to inject automatically.
 *
 * <p>Processors are called once per register call, before
 * the handler is compiled into the router. Returning an empty list is always
 * valid: processors may also use the call purely for side effects
 * (e.g. collecting OpenAPI metadata).
 *
 * <p>Register processors via {@link FlashContext#addAnnotationProcessor}.
 */
@FunctionalInterface
public interface AnnotationProcessor {
    List<MiddlewareNode> process(Class<? extends RequestHandler> handlerClass);
}
