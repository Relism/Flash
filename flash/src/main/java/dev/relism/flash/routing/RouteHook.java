package dev.relism.flash.routing;

import java.util.List;

/**
 * What a module adds to routes it did not write: a policy read from the handler's annotations, a
 * document built from every route. Contributed as a service —
 * {@code app.add(RouteHook.class, SecurityHook.class)} — and asked about every route at boot.
 */
public interface RouteHook {

    /** Middleware for a route served by {@code handler}, a class the app built; a lambda gets none. */
    default List<MiddlewareNode> middleware(Class<?> handler) {
        return List.of();
    }

    /** Called once for every route, after its chain is composed. */
    default void routed(Route route) {}
}
