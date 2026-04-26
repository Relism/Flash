package dev.relism.flash.ext.routeviewer;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves the route-viewer SPA shell ({@code index.html}) from the classpath.
 *
 * <p>The HTML file is produced by the Vite build of {@code routeviewer-ui/}
 * and packaged into the JAR under {@code routeviewer/index.html}.
 * The SPA then fetches {@code /routeviewer/data} for the graph payload.
 */
class RouteViewerHandler {

    private static final String RESOURCE = "routeviewer/index.html";
    private static final String FALLBACK  =
            "<h2 style='font-family:monospace;padding:2rem'>Route Viewer UI not built." +
            "<br>Run: <code>cd routeviewer-ui && pnpm build</code></h2>";

    private volatile byte[] cached;

    Object handle(Request req, Response res) throws IOException {
        if (cached == null) cached = load();
        res.type(ContentType.TEXT_HTML);
        return cached;
    }

    private byte[] load() throws IOException {
        try (InputStream in = RouteViewerHandler.class
                .getClassLoader().getResourceAsStream(RESOURCE)) {
            return in != null ? in.readAllBytes() : FALLBACK.getBytes();
        }
    }
}
