package dev.relism.extension;

import dev.relism.models.SimpleHandler;
import dev.relism.routing.RouteHandle;

/**
 * Common registration surface shared by {@link FlashApp} and {@link FlashScope}.
 *
 * <p>{@link FlashExtension#install} receives a {@code FlashRegistrar} so extensions
 * work identically whether installed at the top-level app or inside a mounted scope.
 *
 * <pre>{@code
 * // In an extension:
 * public void install(FlashRegistrar app, FlashContext ctx) {
 *     app.get("/health", (req, res) -> "ok");
 *     app.get("/secured", (req, res) -> user()).with(oidc.protect());
 * }
 * }</pre>
 */
public interface FlashRegistrar {

    FlashRegistrar install(FlashExtension ext);

    // ── Lambda route registration ────────────────────────────────────────────

    RouteHandle<?> get    (String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> post   (String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> put    (String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> delete (String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> patch  (String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> options(String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> head   (String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> trace  (String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> connect(String path, SimpleHandler.FunctionalHandler h);
    RouteHandle<?> purge  (String path, SimpleHandler.FunctionalHandler h);

    /**
     * Scans {@code packageName} for classes that extend
     * {@link dev.relism.models.RequestHandler} and carry
     * {@link dev.relism.routing.Route @Route}. Each is instantiated via its
     * no-arg constructor, run through annotation processors, and registered.
     */
    FlashRegistrar scan(String packageName);

    /** Returns the {@link FlashContext} for this registrar. */
    FlashContext ctx();
}
