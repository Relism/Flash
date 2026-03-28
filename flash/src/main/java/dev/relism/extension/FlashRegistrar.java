package dev.relism.extension;

import dev.relism.models.RequestHandler;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.AbstractRouter;
import dev.relism.routing.RouteHandle;

/**
 * Common registration surface shared by {@link FlashApp} and {@link FlashScope}.
 *
 * <p>{@link FlashExtension#install} receives a {@code FlashRegistrar} so that extensions
 * work identically whether installed at the top-level app or inside a mounted scope.
 *
 * <p>Route registration follows the auto-flush pattern: calling any registration method
 * without a subsequent {@link RouteHandle#with} is equivalent to calling
 * {@code .with()} with no arguments — the route is registered with no middleware.
 *
 * <pre>{@code
 * // In an extension:
 * public void install(FlashRegistrar app, ExtensionContext ctx) {
 *     app.get("/health", (req, res) -> "ok");
 *     app.get("/secured", (req, res) -> user()).with(oidc.protect());
 * }
 * }</pre>
 */
public interface FlashRegistrar {

    // ── Extension installation ────────────────────────────────────────────────

    FlashRegistrar install(FlashExtension ext);

    // ── Route registration ────────────────────────────────────────────────────

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
     * Begins registration of a class-based handler. The class must carry a
     * {@link dev.relism.routing.Route @Route} annotation. Annotation processors
     * (e.g. {@code @Authenticated}, {@code @RolesAllowed}) are applied automatically.
     *
     * <p>Calling {@link RouteHandle#with} is optional — the route is registered
     * automatically before the next operation or at {@code start()}.
     */
    RouteHandle<?> register(RequestHandler h);

    /**
     * Scans {@code packageName} for classes that extend {@link RequestHandler} and
     * carry {@link dev.relism.routing.Route @Route}. Each is instantiated via its
     * no-arg constructor, run through annotation processors, and registered.
     */
    FlashRegistrar scan(String packageName);

    // ── Error handlers ────────────────────────────────────────────────────────

    FlashRegistrar onException(AbstractRouter.ExceptionHandler h);
    FlashRegistrar onNotFound(SimpleHandler.FunctionalHandler h);

    // ── Context access ────────────────────────────────────────────────────────

    /** Returns the {@link ExtensionContext} for this registrar (app or scope). */
    ExtensionContext ctx();
}
