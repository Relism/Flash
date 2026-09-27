package dev.relism.flash.ext.routeviewer;

import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.Route;

import java.util.ArrayList;
import java.util.List;

/**
 * The routes as the {@code @xyflow/react} graph the viewer draws: {@code {"nodes":[...],"edges":[...]}}.
 *
 * <pre>
 * [router] → [route] → [handler] → [its superclass] → ...
 *   [mw₀] → [mw₁] → ... → [route]
 * </pre>
 */
final class GraphSerializer {

    private GraphSerializer() {}

    static String toJson(List<Route> routes) {
        List<String> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        nodes.add(node("router:/", "router", "{\"namespace\":\"/\",\"routerType\":\"Router\",\"routeCount\":" + routes.size() + "}"));

        for (Route route : routes) {
            String method = route.websocket() ? "WS" : route.method().name();
            String routeId = "route:" + method + ":" + route.path();
            nodes.add(node(routeId, "route", "{\"method\":" + q(method) + ",\"path\":" + q(route.path()) + "}"));
            edges.add(edge(edges.size(), "router:/", routeId, "contains"));

            String previous = null;
            for (int i = 0; i < route.middleware().size(); i++) {
                String id = "mw:" + routeId + ":" + i;
                nodes.add(node(id, "middleware", "{\"name\":" + q(name(route.middleware().get(i))) + ",\"order\":" + i + "}"));
                if (previous != null) edges.add(edge(edges.size(), previous, id, "wraps"));
                previous = id;
            }
            if (previous != null) edges.add(edge(edges.size(), previous, routeId, "wraps"));

            previous = routeId;
            int depth = 0;
            for (Class<?> c = route.handler(); c != null && c != Object.class; c = c.getSuperclass()) {
                String name = c.getName().substring(c.getName().lastIndexOf('.') + 1).replace('$', '.');
                String id = "handler:" + routeId + ":" + name;
                nodes.add(node(id, "handler", "{\"name\":" + q(name) + ",\"depth\":" + depth + "}"));
                edges.add(edge(edges.size(), previous, id, depth++ == 0 ? "handles" : "extends"));
                previous = id;
            }
        }
        return "{\"nodes\":[" + String.join(",", nodes) + "],\"edges\":[" + String.join(",", edges) + "]}";
    }

    /** A lambda's class name without its synthetic {@code $$Lambda/0x...} suffix. */
    private static String name(Class<? extends Middleware> middleware) {
        String simple = middleware.getSimpleName();
        int lambda = simple.indexOf("$$");
        return lambda >= 0 ? simple.substring(0, lambda) : simple;
    }

    private static String node(String id, String type, String data) {
        return "{\"id\":" + q(id) + ",\"type\":" + q(type) + ",\"data\":" + data + "}";
    }

    private static String edge(int index, String source, String target, String type) {
        return "{\"id\":\"e" + index + "\",\"source\":" + q(source) + ",\"target\":" + q(target) + ",\"edgeType\":" + q(type) + "}";
    }

    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
