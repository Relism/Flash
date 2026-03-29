package dev.relism.extension;

/**
 * Observer notified once for each route registered on a {@link FlashApp} or {@link FlashScope}.
 *
 * <p>Register via {@link FlashContext#addRouteListener}. The listener is called
 * <em>once per route at boot time</em>, before the route is handed to the routing engine.
 * There is zero overhead on the request hot-path.
 *
 * <p>Intended for extensions that need to introspect the route graph (e.g. a route viewer,
 * OpenAPI schema builder, etc.) without polluting the routing or middleware infrastructure.
 */
@FunctionalInterface
public interface RouteListener {

    /**
     * Called once for every registered route, in registration order.
     *
     * @param event immutable snapshot of the route's metadata at registration time
     */
    void onRoute(RouteEvent event);
}
