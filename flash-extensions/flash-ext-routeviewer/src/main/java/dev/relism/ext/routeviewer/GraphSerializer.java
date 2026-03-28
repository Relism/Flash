package dev.relism.ext.routeviewer;

import dev.relism.ext.routeviewer.model.RouteGraph;
import dev.relism.ext.routeviewer.model.RouterNode;
import dev.relism.ext.routeviewer.model.RouteRecord;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts a {@link RouteGraph} to the JSON structure consumed by the React frontend.
 *
 * <p>Produces a {@code {"nodes":[...],"edges":[...]}} payload compatible with
 * {@code @xyflow/react}. No external JSON library required — the graph schema is
 * simple and static enough for manual serialization.
 *
 * <h3>Graph structure per route</h3>
 * <pre>
 * [MW₀] → [MW₁] → ... → [RouteNode] → [ConcreteHandler] → [ParentHandler] → ...
 *                             ↑
 *                       [RouterNode]
 * </pre>
 * Middleware is per-route (not deduplicated), handler chain is per-route.
 */
final class GraphSerializer {

    private GraphSerializer() {}

    static String toJson(RouteGraph graph) {
        List<String> nodes = new ArrayList<>();
        List<String> edges = new ArrayList<>();
        int[] eid = {0};

        for (RouterNode router : graph.nodes()) {
            String routerId = "router:" + router.namespace();
            nodes.add(routerNode(routerId, router));

            for (RouteRecord route : router.routes()) {
                String method  = route.event().method().name();
                String path    = route.event().path();
                String routeId = "route:" + method + ":" + path;

                nodes.add(routeNode(routeId, route));
                edges.add(edge("e" + eid[0]++, routerId, routeId, "contains"));

                // Middleware chain: [mw0] → [mw1] → ... → [route]
                List<String> mwNames = route.middlewareNames();
                String prevMwId = null;
                for (int i = 0; i < mwNames.size(); i++) {
                    String mwId = "mw:" + routeId + ":" + i;
                    nodes.add(middlewareNode(mwId, mwNames.get(i), i));
                    if (prevMwId != null)
                        edges.add(edge("e" + eid[0]++, prevMwId, mwId, "wraps"));
                    prevMwId = mwId;
                }
                if (prevMwId != null)
                    edges.add(edge("e" + eid[0]++, prevMwId, routeId, "wraps"));

                // Handler abstraction chain: [route] → [concrete] → [parent] → ...
                List<String> chain = route.abstractionChain();
                String prevHandlerId = null;
                for (int i = 0; i < chain.size(); i++) {
                    String handlerId = "handler:" + routeId + ":" + chain.get(i);
                    nodes.add(handlerNode(handlerId, chain.get(i), i));
                    if (i == 0)
                        edges.add(edge("e" + eid[0]++, routeId, handlerId, "handles"));
                    else
                        edges.add(edge("e" + eid[0]++, prevHandlerId, handlerId, "extends"));
                    prevHandlerId = handlerId;
                }
            }
        }

        return "{\"nodes\":[" + String.join(",", nodes) +
               "],\"edges\":["  + String.join(",", edges) + "]}";
    }

    // ── Node builders ─────────────────────────────────────────────────────────

    private static String routerNode(String id, RouterNode r) {
        return obj("id", id, "type", "router",
                "data", raw("{\"namespace\":" + q(r.namespace()) +
                            ",\"routerType\":" + q(r.routerType()) +
                            ",\"routeCount\":" + r.routes().size() + "}"));
    }

    private static String routeNode(String id, RouteRecord route) {
        return obj("id", id, "type", "route",
                "data", raw("{\"method\":" + q(route.event().method().name()) +
                            ",\"path\":" + q(route.event().path()) + "}"));
    }

    private static String handlerNode(String id, String name, int depth) {
        return obj("id", id, "type", "handler",
                "data", raw("{\"name\":" + q(name) + ",\"depth\":" + depth + "}"));
    }

    private static String middlewareNode(String id, String name, int order) {
        return obj("id", id, "type", "middleware",
                "data", raw("{\"name\":" + q(name) + ",\"order\":" + order + "}"));
    }

    private static String edge(String id, String source, String target, String type) {
        return "{\"id\":" + q(id) + ",\"source\":" + q(source) +
               ",\"target\":" + q(target) + ",\"edgeType\":" + q(type) + "}";
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    /** Builds a JSON object from alternating key/value pairs (values must already be JSON). */
    private static String obj(String k1, String v1, String k2, String v2,
                               String k3, RawJson v3) {
        return "{" + q(k1) + ":" + q(v1) + "," + q(k2) + ":" + q(v2) + "," + q(k3) + ":" + v3.json + "}";
    }

    private static RawJson raw(String json) { return new RawJson(json); }
    private record RawJson(String json) {}

    /** JSON-escapes and quotes a string. */
    private static String q(String s) {
        return "\"" + s.replace("\\", "\\\\")
                       .replace("\"", "\\\"")
                       .replace("\n", "\\n")
                       .replace("\r", "\\r") + "\"";
    }
}
