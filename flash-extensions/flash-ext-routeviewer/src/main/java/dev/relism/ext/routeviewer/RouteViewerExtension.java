package dev.relism.ext.routeviewer;

import dev.relism.ext.routeviewer.model.RouteGraph;
import dev.relism.extension.ExtensionContext;
import dev.relism.extension.FlashExtension;
import dev.relism.extension.FlashRegistrar;
import dev.relism.http.ContentType;

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
 * <h3>Install order</h3>
 * Install <em>after</em> extensions that register annotation processors
 * (e.g. {@code OidcExtension}) but <em>before</em> {@code scan()} or
 * {@code register()} calls so the listener captures all routes:
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new OidcExtension(config))
 *     .install(new JacksonExtension())
 *     .install(new RouteViewerExtension())   // before scan
 *     .scan("dev.example.handlers")
 *     .start();
 * }</pre>
 *
 * <p>All route metadata is collected once at boot time via
 * {@link ExtensionContext#addRouteListener}. Zero overhead on the request hot-path.
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
    public void install(FlashRegistrar app, ExtensionContext ctx) {
        RouteViewerHandler     shell = new RouteViewerHandler();
        RouteViewerDataHandler data  = new RouteViewerDataHandler(graph);

        // Static assets (Vite build output, bundled in JAR)
        app.get(path,              shell::handle);
        app.get(path + "/app.js",  new RouteViewerStaticHandler("routeviewer/app.js",  ContentType.TEXT_JAVASCRIPT)::handle);
        app.get(path + "/app.css", new RouteViewerStaticHandler("routeviewer/app.css", ContentType.TEXT_CSS)::handle);

        // Graph data API — must be registered before the listener so it is
        // flushed and captured AFTER the listener is attached (shows in the graph)
        app.get(path + "/data", data::handle);

        // Start listening — routes registered after this point are captured
        ctx.addRouteListener(graph::add);
    }
}
