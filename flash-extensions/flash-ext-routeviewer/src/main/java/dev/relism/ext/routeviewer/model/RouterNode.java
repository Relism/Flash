package dev.relism.ext.routeviewer.model;

import java.util.List;

/**
 * A node in the route graph representing a single router instance.
 *
 * <p>Routers are identified by their namespace prefix. The hierarchy
 * (parent/child relationships) is inferred by prefix matching — a router
 * with namespace {@code "/api/users"} is a child of {@code "/api"}.
 *
 * @param namespace   the router's namespace prefix (e.g. {@code "/"}, {@code "/api"})
 * @param routerType  simple class name of the router implementation (e.g. {@code "FastPathRouterImpl"})
 * @param routes      routes registered directly on this router, in registration order
 */
public record RouterNode(
        String           namespace,
        String           routerType,
        List<RouteRecord> routes
) {

    /** Returns {@code true} if {@code other} is a direct or indirect parent of this node. */
    public boolean isChildOf(RouterNode other) {
        if (this.namespace.equals(other.namespace)) return false;
        return this.namespace.startsWith(other.namespace.equals("/") ? "/" : other.namespace + "/");
    }
}
