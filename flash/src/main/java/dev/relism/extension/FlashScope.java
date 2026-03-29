package dev.relism.extension;

import dev.relism.exceptions.InitializationException;
import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.Middleware;
import dev.relism.routing.PathUtils;
import dev.relism.routing.Route;
import dev.relism.routing.RouteHandle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Scoped builder for namespace-prefixed routes. Pure syntactic sugar —
 * does not own a router. Collected routes merge into {@link FlashApp}'s
 * single flat router at {@link FlashApp#start()}.
 *
 * <pre>{@code
 * app.mount("/api", scope -> {
 *     scope.use(authMiddleware);
 *     scope.get("/health", (req, res) -> "ok");   // → GET /api/health
 *     scope.scan("dev.example.api.handlers");
 * });
 * }</pre>
 */
public final class FlashScope implements FlashRegistrar {

    private final String                namespace;
    private final FlashContext      ctx;
    private final List<Middleware>      scopeMiddlewares = new ArrayList<>();
    private final List<RouteDefinition> deferredRoutes   = new ArrayList<>();

    private static final Middleware[] NO_MW = new Middleware[0];

    private RouteHandle<?> pending;

    FlashScope(String namespace, FlashContext parentCtx) {
        this.namespace = namespace;
        this.ctx       = parentCtx.child();
    }

    // ── Pending flush ─────────────────────────────────────────────────────────

    private void flushPending() {
        if (pending != null) { pending.ensureRegistered(); pending = null; }
    }

    private <P> RouteHandle<P> track(RouteHandle<P> handle) {
        flushPending();
        pending = handle;
        return handle;
    }

    // ── Scope middleware ──────────────────────────────────────────────────────

    /**
     * Adds middlewares applied to every route in this scope.
     * Combined at compile time in order: Global → Scope → Annotation → Explicit.
     */
    public FlashScope use(Middleware... middlewares) {
        flushPending();
        scopeMiddlewares.addAll(Arrays.asList(middlewares));
        return this;
    }

    // ── Extension installation ────────────────────────────────────────────────

    @Override
    public FlashScope install(FlashExtension ext) {
        flushPending();
        ext.install(this, ctx);
        return this;
    }

    // ── Route registration (deferred) ─────────────────────────────────────────

    @Override public RouteHandle<FlashScope> get    (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.GET,     path, h); }
    @Override public RouteHandle<FlashScope> post   (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.POST,    path, h); }
    @Override public RouteHandle<FlashScope> put    (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.PUT,     path, h); }
    @Override public RouteHandle<FlashScope> delete (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.DELETE,  path, h); }
    @Override public RouteHandle<FlashScope> patch  (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.PATCH,   path, h); }
    @Override public RouteHandle<FlashScope> options(String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.OPTIONS, path, h); }
    @Override public RouteHandle<FlashScope> head   (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.HEAD,    path, h); }
    @Override public RouteHandle<FlashScope> trace  (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.TRACE,   path, h); }
    @Override public RouteHandle<FlashScope> connect(String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.CONNECT, path, h); }
    @Override public RouteHandle<FlashScope> purge  (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.PURGE,   path, h); }

    private RouteHandle<FlashScope> lambda(HttpMethod method, String path, SimpleHandler.FunctionalHandler h) {
        String full = ns(path);
        Middleware[] scopeMw = snapshotScopeMiddlewares();
        return track(new RouteHandle<>(this, mw ->
                deferredRoutes.add(new RouteDefinition(method, full, new SimpleHandler(h), scopeMw, mw, false, ctx, namespace))));
    }

    /**
     * Registers a class-based handler annotated with {@link Route @Route}.
     * Not part of {@link FlashRegistrar} — scope-only convenience.
     */
    RouteHandle<FlashScope> register(RequestHandler handler) {
        Route ann = handler.getClass().getAnnotation(Route.class);
        if (ann == null)
            throw new InitializationException(
                    handler.getClass().getName() + " is missing @Route");
        String full = ns(ann.path());
        Middleware[] scopeMw = snapshotScopeMiddlewares();
        return track(new RouteHandle<>(this, mw ->
                deferredRoutes.add(new RouteDefinition(ann.method(), full, handler, scopeMw, mw, true, ctx, namespace))));
    }

    @Override
    public FlashScope scan(String packageName) {
        flushPending();
        PackageScanner.findHandlers(packageName).forEach(cls ->
                register(instantiate(cls)).ensureRegistered());
        return this;
    }

    @Override
    public FlashContext ctx() { return ctx; }

    // ── Internals (called by FlashApp.mount) ──────────────────────────────────

    void flush() { flushPending(); }

    List<RouteDefinition> routes() { return deferredRoutes; }

    private String ns(String path) {
        return namespace + PathUtils.sanitize(path);
    }

    private Middleware[] snapshotScopeMiddlewares() {
        return scopeMiddlewares.isEmpty() ? NO_MW : scopeMiddlewares.toArray(NO_MW);
    }

    private static RequestHandler instantiate(Class<?> cls) {
        try {
            return (RequestHandler) cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new InitializationException(
                    "Failed to instantiate " + cls.getName() +
                    " — ensure it has a public no-arg constructor", e);
        }
    }
}
