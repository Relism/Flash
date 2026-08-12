package dev.relism.flash.extension;

import dev.relism.flash.exceptions.InitializationException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.SimpleHandler;
import dev.relism.flash.websocket.WebSocketEndpoint;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareKey;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.Routes;
import dev.relism.flash.routing.Ws;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Common route-registration surface shared by {@link FlashApp} and {@link FlashScope}.
 *
 * <p>All route methods register <em>immediately</em> — no deferred {@code .with()} call.
 * Middleware is passed as a varargs third argument:
 *
 * <pre>{@code
 * app.get("/ping",    (req, res) -> "pong")
 * app.get("/secured", (req, res) -> user(), oidc.protect())
 * app.get("/admin",   handler, oidc.requireRole("admin"), rateLimiter)
 * }</pre>
 *
 * <p>Extensions receive a {@link FlashApp} during their single configure declaration.
 * Extension installation ({@code install()}) is only available on
 * {@link FlashApp} — scoped install is intentionally unsupported.
 *
 * @param <SELF> concrete registrar type — enables fluent chaining without casting
 */
@SuppressWarnings("unchecked")
public abstract class FlashRegistrar<SELF extends FlashRegistrar<SELF>> {
    private static final AtomicLong INLINE_KEYS = new AtomicLong();

    // ── HTTP method registration ──────────────────────────────────────────────

    public final SELF get    (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.GET,     path, h, mw); }
    public final SELF post   (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.POST,    path, h, mw); }
    public final SELF put    (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.PUT,     path, h, mw); }
    public final SELF delete (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.DELETE,  path, h, mw); }
    public final SELF patch  (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.PATCH,   path, h, mw); }
    public final SELF options(String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.OPTIONS, path, h, mw); }
    public final SELF head   (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.HEAD,    path, h, mw); }
    public final SELF trace  (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.TRACE,   path, h, mw); }
    public final SELF connect(String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.CONNECT, path, h, mw); }
    public final SELF purge  (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.PURGE,   path, h, mw); }
    public final SELF query  (String path, SimpleHandler.FunctionalHandler h, Middleware... mw) { return route(HttpMethod.QUERY,   path, h, mw); }

    public final SELF getWith    (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.GET,     path, h, mw); }
    public final SELF postWith   (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.POST,    path, h, mw); }
    public final SELF putWith    (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.PUT,     path, h, mw); }
    public final SELF deleteWith (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.DELETE,  path, h, mw); }
    public final SELF patchWith  (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.PATCH,   path, h, mw); }
    public final SELF optionsWith(String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.OPTIONS, path, h, mw); }
    public final SELF headWith   (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.HEAD,    path, h, mw); }
    public final SELF traceWith  (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.TRACE,   path, h, mw); }
    public final SELF connectWith(String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.CONNECT, path, h, mw); }
    public final SELF purgeWith  (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.PURGE,   path, h, mw); }
    public final SELF queryWith  (String path, SimpleHandler.FunctionalHandler h, MiddlewareNode... mw) { return route(HttpMethod.QUERY,   path, h, mw); }

    // ── Middleware ────────────────────────────────────────────────────────────

    /**
     * Adds middlewares to this registrar's own scope.
     * On {@link FlashApp}: applied to every route on the app.
     * On {@link FlashScope}: applied to every route in this scope only.
     * Order-independent: middleware is resolved at {@link FlashApp#start()}.
     */
    public final SELF use(Middleware... middlewares) {
        for (Middleware m : middlewares) addMiddleware(inline(m));
        return (SELF) this;
    }

    /** Adds named middleware nodes whose ordering constraints are compiled at boot. */
    public final SELF use(MiddlewareNode... middlewares) {
        for (MiddlewareNode m : middlewares) addMiddleware(m);
        return (SELF) this;
    }

    // ── Scan ─────────────────────────────────────────────────────────────────

    /**
     * Scans {@code packageName} for {@link RequestHandler} subclasses with a routing
     * annotation ({@link Route @Route} or {@code @GET}, {@code @POST}, …).
     * Each match is constructed with its public no-arg constructor and registered immediately.
     *
     * @throws InitializationException if the package is empty, not found, or any handler
     *         class fails to load or instantiate
     */
    public final SELF scan(String packageName) {
        PackageScanner.ScanResult found = PackageScanner.scan(packageName);
        found.httpHandlers().forEach(cls -> {
            Route ann = Routes.of(cls);
            addRoute(ann.method(), ann.path(), instantiate(cls), List.of());
        });
        found.wsEndpoints().forEach(cls -> {
            Ws ann = cls.getAnnotation(Ws.class);
            addWsRoute(ann.value(), instantiateWs(cls));
        });
        return (SELF) this;
    }

    /** Returns the {@link FlashContext} for this registrar. */
    public abstract FlashContext ctx();

    // ── Infrastructure ────────────────────────────────────────────────────────

    /**
     * Adds a route immediately to this registrar's deferred compilation list.
     * Subclasses may prepend a namespace prefix and inject scope middlewares before storing.
     */
    protected abstract void addRoute(HttpMethod method, String path,
                                     RequestHandler handler, List<MiddlewareNode> mw);

    protected abstract void addWsRoute(String path, WebSocketEndpoint endpoint);

    /** Registers a middleware in this registrar's own scope (global or scope-level). */
    protected abstract void addMiddleware(MiddlewareNode mw);

    private SELF route(HttpMethod method, String path, SimpleHandler.FunctionalHandler h, Middleware[] mw) {
        MiddlewareNode[] nodes = new MiddlewareNode[mw.length];
        for (int i = 0; i < mw.length; i++) nodes[i] = inline(mw[i]);
        addRoute(method, path, new SimpleHandler(h), mw.length == 0 ? List.of() : List.of(nodes));
        return (SELF) this;
    }

    private SELF route(HttpMethod method, String path, SimpleHandler.FunctionalHandler h, MiddlewareNode[] mw) {
        addRoute(method, path, new SimpleHandler(h), mw.length == 0 ? List.of() : List.of(mw));
        return (SELF) this;
    }

    private static MiddlewareNode inline(Middleware middleware) {
        return MiddlewareNode.of(MiddlewareKey.of("flash.inline." + INLINE_KEYS.incrementAndGet()), middleware);
    }

    protected static RequestHandler instantiate(Class<?> cls) {
        try { return (RequestHandler) cls.getDeclaredConstructor().newInstance(); }
        catch (Exception e) {
            throw new InitializationException(
                    "Failed to instantiate " + cls.getName() +
                    " — ensure it has a public no-arg constructor", e);
        }
    }

    protected static WebSocketEndpoint instantiateWs(Class<?> cls) {
        try { return (WebSocketEndpoint) cls.getDeclaredConstructor().newInstance(); }
        catch (Exception e) {
            throw new InitializationException(
                    "Failed to instantiate WS endpoint " + cls.getName() +
                    " — ensure public no-arg constructor", e);
        }
    }
}
