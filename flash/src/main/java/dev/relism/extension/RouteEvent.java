package dev.relism.extension;

import dev.relism.http.HttpMethod;
import dev.relism.routing.Middleware;

import java.util.List;

/**
 * Immutable snapshot of a route captured at registration time.
 *
 * <p>Emitted by {@link FlashApp} and {@link FlashScope} to every registered
 * {@link RouteListener} before the route is compiled into the routing engine.
 * All information is derived automatically — no annotations or declarations
 * are required from the developer.
 *
 * <h3>Middleware chain</h3>
 * {@link #middlewareChain} lists the {@link Middleware} classes outermost-first:
 * <ol>
 *   <li>Router-level middlewares (set on the router constructor)</li>
 *   <li>Annotation-injected middlewares (e.g. from {@code @Authenticated})</li>
 *   <li>Handler-level explicit middlewares (passed inline: {@code app.get(path, h, mw...)})</li>
 * </ol>
 *
 * <h3>Handler abstraction chain</h3>
 * Walk {@link #handlerClass} upward via {@link Class#getSuperclass()} to reconstruct
 * the full inheritance chain (e.g. {@code EditPostPageHandler → HtmlHandler → RequestHandler}).
 * Read {@link Class#getAnnotations()} on each level to discover declared pointcuts
 * ({@code @Authenticated}, {@code @RolesAllowed}, {@code @Route}/{@code @GET}, etc.).
 *
 * @param method          HTTP method for this route
 * @param path            full path as declared (including namespace prefix for scoped routes)
 * @param namespace       namespace prefix of the router that owns this route ({@code "/"} for root)
 * @param routerType      simple class name of the owning router implementation
 * @param handlerClass    concrete handler class, or {@code null} for anonymous lambda handlers
 * @param middlewareChain ordered middleware classes, outermost first
 */
public record RouteEvent(
        HttpMethod      method,
        String          path,
        String          namespace,
        String          routerType,
        Class<?>        handlerClass,
        List<Class<? extends Middleware>> middlewareChain
) {}
