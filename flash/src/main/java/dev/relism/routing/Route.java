package dev.relism.routing;

import dev.relism.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the HTTP method and path for a class-based {@link dev.relism.models.RequestHandler}.
 *
 * <p>The path is relative to the namespace of the router the handler is registered on.
 * For example, registering a handler with {@code path = "/profile"} on a router mounted
 * at {@code /api} results in the effective route {@code /api/profile}.
 *
 * <p>Used by {@link dev.relism.routing.AbstractRouter#register}.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Route {
    HttpMethod method() default HttpMethod.GET;
    String path();
}
