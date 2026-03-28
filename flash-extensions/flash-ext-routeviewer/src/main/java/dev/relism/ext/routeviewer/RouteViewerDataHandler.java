package dev.relism.ext.routeviewer;

import dev.relism.ext.routeviewer.model.RouteGraph;
import dev.relism.http.ContentType;
import dev.relism.models.Request;
import dev.relism.models.Response;

/**
 * Serves {@code GET /routeviewer/data} — the JSON payload consumed by the React SPA.
 *
 * <p>The {@link RouteGraph} is frozen at startup; this handler is pure read-only
 * and produces no allocations beyond the response string itself.
 */
class RouteViewerDataHandler {

    private final RouteGraph graph;
    /** Cached once — the graph never changes after boot. */
    private volatile String cachedJson;

    RouteViewerDataHandler(RouteGraph graph) {
        this.graph = graph;
    }

    Object handle(Request req, Response res) {
        if (cachedJson == null) cachedJson = GraphSerializer.toJson(graph);
        res.setContentType(ContentType.JSON);
        res.header("Cache-Control", "no-cache");
        return cachedJson;
    }
}
