package dev.relism.ext.routeviewer;

import dev.relism.http.ContentType;
import dev.relism.models.Request;
import dev.relism.models.Response;

import java.io.IOException;
import java.io.InputStream;

/**
 * Serves a single static file from the classpath (bundled inside the JAR).
 *
 * <p>Used to expose the Vite-built assets ({@code app.js}, {@code app.css})
 * that the route-viewer SPA needs.
 */
class RouteViewerStaticHandler {

    private final String      classpathResource;
    private final ContentType contentType;
    /** Cached bytes — static assets never change after startup. */
    private volatile byte[] cached;

    RouteViewerStaticHandler(String classpathResource, ContentType contentType) {
        this.classpathResource = classpathResource;
        this.contentType       = contentType;
    }

    Object handle(Request req, Response res) throws IOException {
        if (cached == null) cached = load();
        if (cached == null) {
            res.setStatusCode(404);
            return null;
        }
        res.setStatusCode(200);
        res.setContentType(contentType);
        res.header("Cache-Control", "public, max-age=3600");
        return cached;
    }

    private byte[] load() throws IOException {
        try (InputStream in = RouteViewerStaticHandler.class
                .getClassLoader().getResourceAsStream(classpathResource)) {
            return in == null ? null : in.readAllBytes();
        }
    }
}
