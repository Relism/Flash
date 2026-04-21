package dev.relism.extension;

import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.Middleware;
import dev.relism.routing.PathUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Scoped builder for namespace-prefixed routes. Pure syntactic sugar —
 * does not own a router. Routes merge into {@link FlashApp}'s single flat router
 * at {@link FlashApp#start()}.
 *
 * <pre>{@code
 * app.mount("/api", scope -> {
 *     scope.use(authMiddleware);
 *     scope.get("/health", (req, res) -> "ok");                    // → GET /api/health
 *     scope.get("/users",  (req, res) -> "...", rateLimiter);      // → GET /api/users
 *     scope.scan("dev.example.api.handlers");
 * });
 * }</pre>
 */
public final class FlashScope extends FlashRegistrar<FlashScope> {

    private final String                namespace;
    private final FlashContext          ctx;
    private final List<Middleware>      scopeMiddlewares = new ArrayList<>();
    private final List<RouteDefinition> deferredRoutes   = new ArrayList<>();

    FlashScope(String namespace, FlashContext parentCtx) {
        this.namespace = PathUtils.sanitize(namespace);
        this.ctx       = parentCtx.child();
    }

    @Override public FlashContext ctx() { return ctx; }

    // ── FlashRegistrar impl ───────────────────────────────────────────────────

    @Override
    protected void addRoute(HttpMethod method, String path, RequestHandler handler, List<Middleware> mw) {
        List<Middleware> scopeMw = scopeMiddlewares.isEmpty() ? List.of() : List.copyOf(scopeMiddlewares);
        deferredRoutes.add(new RouteDefinition(
                method, ns(path), handler, scopeMw, mw,
                !(handler instanceof SimpleHandler), ctx, namespace));
    }

    @Override
    protected void addMiddleware(Middleware mw) { scopeMiddlewares.add(mw); }

    // ── Internal (called by FlashApp.mount) ───────────────────────────────────

    List<RouteDefinition> routes() { return deferredRoutes; }

    private String ns(String path) { return PathUtils.join(namespace, path); }
}
