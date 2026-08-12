package dev.relism.flash.extension;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.routing.MiddlewareNode;

import java.util.List;

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
 * @param explicitMiddlewares middlewares passed inline at registration ({@code app.get(path, handler, mw...)})
 * @param classBasedHandler   true → needs annotation processing + context binding
 * @param ctx                 the FlashContext for this route's binding and processors
 * @param namespace           logical namespace for route events ("/" for app, "/api" for scope, etc.)
 */
record RouteDefinition(
        HttpMethod method,
        String path,
        RequestHandler handler,
        List<MiddlewareNode> scopeMiddlewares,
        List<MiddlewareNode> explicitMiddlewares,
        boolean classBasedHandler,
        FlashContext ctx,
        String namespace
) {}
