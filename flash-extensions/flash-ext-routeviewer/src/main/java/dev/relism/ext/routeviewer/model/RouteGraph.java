package dev.relism.ext.routeviewer.model;

import dev.relism.extension.RouteEvent;

import java.util.*;

/**
 * Accumulates {@link RouteEvent}s at boot time and organizes them into an
 * ordered list of {@link RouterNode}s for rendering.
 *
 * <p>Thread-safety: events are emitted sequentially at registration time
 * (single-threaded boot), so no synchronization is needed here.
 */
public class RouteGraph {

    /** Events in registration order, grouped by namespace. */
    private final Map<String, List<RouteRecord>> byNamespace = new LinkedHashMap<>();
    /** Namespace → routerType, filled on first event for each namespace. */
    private final Map<String, String>            routerTypes = new LinkedHashMap<>();

    /** Called once per route by the {@link dev.relism.extension.RouteListener}. */
    public void add(RouteEvent event) {
        routerTypes.putIfAbsent(event.namespace(), event.routerType());
        byNamespace
                .computeIfAbsent(event.namespace(), k -> new ArrayList<>())
                .add(RouteRecord.from(event));
    }

    /**
     * Returns the route graph as an ordered list of {@link RouterNode}s,
     * sorted from shortest namespace to longest (root first, deepest last).
     */
    public List<RouterNode> nodes() {
        return byNamespace.entrySet().stream()
                .sorted(Comparator.comparingInt(e -> e.getKey().length()))
                .map(e -> new RouterNode(e.getKey(), routerTypes.get(e.getKey()), List.copyOf(e.getValue())))
                .toList();
    }

    /** Total number of registered routes across all routers. */
    public int totalRoutes() {
        return byNamespace.values().stream().mapToInt(List::size).sum();
    }
}
