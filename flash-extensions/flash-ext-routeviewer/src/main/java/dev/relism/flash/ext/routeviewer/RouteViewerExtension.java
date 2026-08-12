package dev.relism.flash.ext.routeviewer;

import dev.relism.flash.ext.routeviewer.model.RouteGraph;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.http.ContentType;

/**
 * Mounts an interactive route-graph viewer at a configurable HTTP endpoint.
 *
 * <p>The viewer is a React SPA ({@code @xyflow/react}) bundled into the JAR.
 * It renders routers, routes, handler inheritance chains, and middleware chains
 * as a draggable, zoomable node graph.
 *
 * <h3>Endpoints registered</h3>
 * <ul>
 *   <li>{@code GET <path>}         — SPA shell (index.html)</li>
 *   <li>{@code GET <path>/app.js}  — React bundle</li>
 *   <li>{@code GET <path>/app.css} — styles</li>
 *   <li>{@code GET <path>/data}    — graph JSON consumed by the SPA</li>
 * </ul>
 *
 * <p>The route listener is registered during the {@link #provide} phase, before any routes
 * are compiled. Combined with the two-phase extension model, this guarantees all routes
 * (including those from other extensions) are always captured — install order is irrelevant.
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new OidcExtension(config))
 *     .install(new RouteViewerExtension())
 *     .scan("dev.example.handlers")
 *     .start();
 * }</pre>
 */
public class RouteViewerExtension implements FlashExtension {

    public static final String DEFAULT_PATH = "/routeviewer";

    private final String     path;
    private final RouteGraph graph = new RouteGraph();

    /** Installs the viewer at {@value #DEFAULT_PATH}. */
    public RouteViewerExtension() { this(DEFAULT_PATH); }

    /**
     * Installs the viewer at a custom path.
     * @param path e.g. {@code "/_routes"}
     */
    public RouteViewerExtension(String path) { this.path = path; }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        // Register listener before any routes compile — captures everything.
        ctx.addRouteListener(graph::add);
        ctx.onReady(() -> {
            app.get(path,              new RouteViewerHandler()::handle);
            app.get(path + "/app.js",  new RouteViewerStaticHandler("routeviewer/app.js",  ContentType.TEXT_JAVASCRIPT)::handle);
            app.get(path + "/app.css", new RouteViewerStaticHandler("routeviewer/app.css", ContentType.TEXT_CSS)::handle);
            app.get(path + "/data",    new RouteViewerDataHandler(graph)::handle);
        });
    }
}
