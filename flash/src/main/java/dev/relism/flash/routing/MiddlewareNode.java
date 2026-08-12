package dev.relism.flash.routing;

import java.util.*;

/**
 * A named middleware contribution and its ordering constraints.
 *
 * <p>Constraints are resolved only while Flash compiles a route. The resulting handler chain
 * contains no keys, graphs, ordering checks or additional request-path allocations.
 */
public final class MiddlewareNode {
    private final MiddlewareKey key;
    private final Middleware middleware;
    private final List<Constraint> constraints = new ArrayList<>();

    private MiddlewareNode(MiddlewareKey key, Middleware middleware) {
        this.key = Objects.requireNonNull(key, "key");
        this.middleware = Objects.requireNonNull(middleware, "middleware");
    }

    public static MiddlewareNode of(MiddlewareKey key, Middleware middleware) { return new MiddlewareNode(key, middleware); }
    public MiddlewareNode after(MiddlewareKey key) { constraints.add(new Constraint(key, Relation.AFTER, true)); return this; }
    public MiddlewareNode afterIfPresent(MiddlewareKey key) { constraints.add(new Constraint(key, Relation.AFTER, false)); return this; }
    public MiddlewareNode before(MiddlewareKey key) { constraints.add(new Constraint(key, Relation.BEFORE, true)); return this; }
    public MiddlewareNode beforeIfPresent(MiddlewareKey key) { constraints.add(new Constraint(key, Relation.BEFORE, false)); return this; }

    public MiddlewareKey key() { return key; }
    Middleware middleware() { return middleware; }
    List<Constraint> constraints() { return List.copyOf(constraints); }

    enum Relation { BEFORE, AFTER }
    record Constraint(MiddlewareKey target, Relation relation, boolean required) {
        Constraint { Objects.requireNonNull(target, "target"); }
    }
}
