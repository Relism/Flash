package dev.relism.flash.routing;

import dev.relism.flash.http.Handler;

import java.util.*;

/** Boot-only DAG compiler for a route's middleware nodes. */
public final class MiddlewareGraph {
    private MiddlewareGraph() {}

    public static Middleware[] order(String route, List<MiddlewareNode> nodes) {
        if (nodes.isEmpty()) return new Middleware[0];
        Map<MiddlewareKey, Integer> index = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            MiddlewareKey key = nodes.get(i).key();
            if (index.putIfAbsent(key, i) != null)
                throw new IllegalStateException("Duplicate middleware " + key.value() + " on " + route);
        }
        List<Set<Integer>> outgoing = new ArrayList<>(nodes.size());
        int[] incoming = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) outgoing.add(new LinkedHashSet<>());
        for (int source = 0; source < nodes.size(); source++) {
            for (MiddlewareNode.Constraint c : nodes.get(source).constraints()) {
                Integer target = index.get(c.target());
                if (target == null) {
                    if (c.required()) throw new IllegalStateException("Middleware " + nodes.get(source).key().value()
                            + " on " + route + " requires " + c.target().value() + " to be present");
                    continue;
                }
                int from = c.relation() == MiddlewareNode.Relation.AFTER ? target : source;
                int to = c.relation() == MiddlewareNode.Relation.AFTER ? source : target;
                if (outgoing.get(from).add(to)) incoming[to]++;
            }
        }
        PriorityQueue<Integer> ready = new PriorityQueue<>();
        for (int i = 0; i < incoming.length; i++) if (incoming[i] == 0) ready.add(i);
        Middleware[] ordered = new Middleware[nodes.size()];
        int out = 0;
        while (!ready.isEmpty()) {
            int current = ready.remove();
            ordered[out++] = nodes.get(current).middleware();
            for (int next : outgoing.get(current)) if (--incoming[next] == 0) ready.add(next);
        }
        if (out != nodes.size()) throw new IllegalStateException("Middleware ordering cycle on " + route);
        return ordered;
    }

    /** The chain around {@code handler}, composed once at boot: the first middleware runs first. */
    public static Handler compose(Handler handler, Middleware[] ordered) {
        Handler current = handler;
        for (int i = ordered.length - 1; i >= 0; i--) current = ordered[i].wrap(current);
        return current;
    }
}
