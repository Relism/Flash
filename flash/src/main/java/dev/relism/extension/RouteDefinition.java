package dev.relism.extension;

import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.routing.Middleware;

/**
 * Immutable snapshot of a route captured during the builder phase.
 *
 * <p>Accumulated by {@link FlashApp} and {@link FlashScope}. All definitions —
 * regardless of origin — are compiled into a single flat router at
 * {@link FlashApp#start()}.
 *
 * @param method              HTTP method
 * @param path                fully resolved path (namespace already prepended for scope routes)
 * @param handler             SimpleHandler for lambdas, user handler for class-based
 * @param scopeMiddlewares    middlewares from {@link FlashScope#use} (empty for app-level routes)
 * @param explicitMiddlewares middlewares from {@link dev.relism.routing.RouteHandle#with}
 * @param classBasedHandler   true → needs annotation processing + context binding
 * @param ctx                 the FlashContext for this route's binding and processors
 * @param namespace           logical namespace for route events ("/" for app, "/api" for scope, etc.)
 */
record RouteDefinition(
        HttpMethod method,
        String path,
        RequestHandler handler,
        Middleware[] scopeMiddlewares,
        Middleware[] explicitMiddlewares,
        boolean classBasedHandler,
        FlashContext ctx,
        String namespace
) {}
