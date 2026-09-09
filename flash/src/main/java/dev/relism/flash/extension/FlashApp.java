package dev.relism.flash.extension;

import dev.relism.flash.Flash;
import dev.relism.flash.ServerHandle;
import dev.relism.flash.exceptions.InitializationException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.SimpleHandler;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.AbstractWsRouter;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareGraph;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathRouterImpl;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathWsRouterImpl;
import dev.relism.flash.websocket.WebSocketEndpoint;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Single entry point for Flash. Owns one flat {@link FastPathRouterImpl} —
 * all routes (app-level and scoped) compile into a single FSM at startup.
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new JacksonExtension())
 *     .use(cors)
 *     .get("/ping",    (req, res) -> "pong")
 *     .get("/secured", (req, res) -> user(), oidc.protect())
 *     .scan("dev.example.handlers")
 *     .mount("/api", scope -> scope.get("/health", (req, res) -> "ok"))
 *     .startAndBlock();   // blocks main thread
 * }</pre>
 *
 * <h3>Startup sequence (both {@link #start()} and {@link #startAndBlock()})</h3>
 * <ol>
 *   <li>All {@link FlashExtension#configure} declarations.</li>
 *   <li>{@link FlashContext#resolveAll()} — topo-sort, cycle detection.</li>
 *   <li>Ready callbacks register routes with resolved services.</li>
 *   <li>Compile all routes into one flat FSM — zero prefix scanning at runtime.</li>
 *   <li>Accept loop started.</li>
 * </ol>
 *
 * <h3>Default error handlers</h3>
 * Dev mode ({@code -Dflash.env=dev}): rich HTML with stack traces.
 * Prod mode (default): generic JSON — no internal details leaked.
 * Override via {@link #onException} / {@link #onNotFound}.
 */
@Slf4j
public final class FlashApp extends FlashRegistrar<FlashApp> {

    private final AbstractRouter        router     = new FastPathRouterImpl();
    private final AbstractWsRouter      wsRouter   = new FastPathWsRouterImpl();
    private final ServerHandle          server;
    private final FlashContext          ctx        = new FlashContext();
    private final List<FlashExtension>  extensions        = new ArrayList<>();
    private final List<MiddlewareNode>  globalMiddlewares = new ArrayList<>();
    private final List<RouteDefinition> deferredRoutes    = new ArrayList<>();
    private final List<WsRouteDefinition> deferredWsRoutes = new ArrayList<>();

    private record WsRouteDefinition(String path, WebSocketEndpoint endpoint) {}

    private FlashApp(FlashConfiguration config) {
        try {
            this.server = ServerHandle.create(config, router, wsRouter);
        }
        catch (IOException e) { throw new InitializationException("Failed to bind on port " + config.getPort(), e); }
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    public static FlashApp create(int port)               { return create(FlashConfiguration.builder().port(port).build()); }
    public static FlashApp create(FlashConfiguration cfg) { return new FlashApp(cfg); }

    // ── Namespace mounting ────────────────────────────────────────────────────

    /**
     * Mounts a scoped group of routes under {@code namespace}. Routes accumulate in a
     * {@link FlashScope} builder and merge into this app's single flat router at startup —
     * pure syntactic sugar, no extra router involved.
     */
    public FlashApp mount(String namespace, Consumer<FlashScope> configure) {
        FlashScope scope = new FlashScope(namespace, ctx);
        configure.accept(scope);
        deferredRoutes.addAll(scope.routes());
        deferredWsRoutes.addAll(scope.wsRoutes().stream()
                .map(r -> new WsRouteDefinition(r.path(), r.endpoint()))
                .toList());
        return this;
    }

    /**
     * Registers a WebSocket endpoint at {@code path}.
     * Runs on the same virtual-thread model as HTTP handlers.
     * A path may have both an HTTP handler and a WS endpoint simultaneously.
     */
    public FlashApp ws(String path, WebSocketHandler handler) {
        WebSocketEndpoint endpoint = handler instanceof WebSocketEndpoint e ? e
                : new WebSocketEndpoint() {
                    @Override public void onOpen(WebSocketSession s) { handler.onOpen(s); }
                    @Override public void onMessage(WebSocketSession s, WebSocketFrame f) { handler.onMessage(s, f); }
                    @Override public void onClose(WebSocketSession s, int c) { handler.onClose(s, c); }
                    @Override public void onError(WebSocketSession s, Throwable t) { handler.onError(s, t); }
                };
        deferredWsRoutes.add(new WsRouteDefinition(path, endpoint));
        return this;
    }

    // ── Error handlers ────────────────────────────────────────────────────────

    public FlashApp onException(AbstractRouter.ExceptionHandler handler) {
        router.onException(handler);
        return this;
    }

    public FlashApp onNotFound(SimpleHandler.FunctionalHandler handler) {
        router.onNotFound(handler);
        return this;
    }

    @Override public FlashContext ctx() { return ctx; }

    // ── Extensions ────────────────────────────────────────────────────────────

    /**
     * Registers a declarative extension contribution.
     */
    public FlashApp install(FlashExtension ext) {
        extensions.add(ext);
        return this;
    }

    /**
     * Applies a {@link FlashApplication} to this app. Runs immediately, so {@code configure}
     * sees a context that is still open for declarations and a listener that is already
     * bound ({@link #port()}).
     */
    public FlashApp apply(FlashApplication application) {
        Objects.requireNonNull(application, "application").configure(this);
        return this;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Boots the server and starts accepting connections. Returns immediately — the
     * accept loop runs on a background platform thread.
     *
     * <p>Use {@link #startAndBlock()} in {@code main()} if you have no other work to do
     * after startup.
     *
     * @return {@code this} for storing or chaining (e.g. {@code .start().ctx().require(...)})
     */
    public FlashApp start() {
        boot();
        server.start();
        if (Flash.DEV) log.info("[Flash] Started (dev mode) — see HttpServer bind logs above for listener details");
        return this;
    }

    /**
     * Boots the server and blocks the calling thread until the server is stopped.
     * Suitable for the end of {@code main()} — no need to keep the JVM alive manually.
     */
    public void startAndBlock() {
        boot();
        if (Flash.DEV) log.info("[Flash] Started (dev mode) — see HttpServer bind logs above for listener details");
        server.startAndBlock();
    }

    /**
     * Gracefully stops the server, then runs every {@link FlashContext#onClose} callback so
     * services release what they hold. Draining first means in-flight requests still see a
     * live service graph.
     */
    public CompletableFuture<Void> stop() {
        return server.stop().whenComplete((ignored, failure) -> ctx.runCloseCallbacks());
    }

    /**
     * Port of the first bound listener. Usable before {@link #start()}: listeners bind when
     * the app is created, so {@code FlashApp.create(cfg with port 0).port()} yields the
     * OS-assigned port, and an app can even be configured against its own address.
     */
    public int port() { return server.port(); }

    /** Ports of every bound listener, in configuration order. */
    public List<Integer> ports() { return server.ports(); }

    // ── FlashRegistrar impl ───────────────────────────────────────────────────

    @Override
    protected void addRoute(HttpMethod method, String path, RequestHandler handler, List<MiddlewareNode> mw) {
        deferredRoutes.add(new RouteDefinition(
                method, path, handler, List.of(), mw,
                !(handler instanceof SimpleHandler), ctx, "/"));
    }

    @Override
    protected void addWsRoute(String path, WebSocketEndpoint endpoint) {
        deferredWsRoutes.add(new WsRouteDefinition(path, endpoint));
    }

    @Override
    protected void addMiddleware(MiddlewareNode mw) { globalMiddlewares.add(mw); }

    // ── Boot ─────────────────────────────────────────────────────────────────

    private void boot() {
        extensions.forEach(e -> e.configure(this, ctx));
        ctx.complete();
        compile();
        compileWs();
        router.compile();
        wsRouter.compile();
    }

    // ── Compilation ──────────────────────────────────────────────────────────

    /** Middleware chain order per route: Global → Scope → Annotation → Explicit. */
    private void compile() {
        for (RouteDefinition def : deferredRoutes) {
            List<MiddlewareNode> injected;
            if (def.classBasedHandler()) {
                injected = def.ctx().processors().stream()
                        .flatMap(p -> p.process(def.handler().getClass()).stream())
                        .toList();
                def.handler().bind(def.ctx());
            } else {
                injected = List.of();
            }
            Middleware[] all = MiddlewareGraph.order(def.method() + " " + def.path(),
                    concat(globalMiddlewares, def.scopeMiddlewares(), injected, def.explicitMiddlewares()));
            emitEvent(def, all);
            router.doRegister(def.method(), def.path(), def.handler(), all);
        }
    }

    private void compileWs() {
        for (WsRouteDefinition def : deferredWsRoutes) {
            def.endpoint().bind(ctx);
            emitWsEvent(def.path(), def.endpoint());
            wsRouter.register(HttpMethod.GET, def.path(), def.endpoint());
        }
    }

    @SuppressWarnings("unchecked")
    private void emitEvent(RouteDefinition def, Middleware[] chain) {
        List<RouteListener> listeners = def.ctx().routeListeners();
        if (listeners.isEmpty()) return;
        List<Class<? extends Middleware>> mwClasses = new ArrayList<>(chain.length);
        for (Middleware m : chain) mwClasses.add((Class<? extends Middleware>) m.getClass());
        Class<?> handlerClass = def.classBasedHandler() ? def.handler().getClass() : null;
        RouteEvent event = new RouteEvent(def.method(), def.path(), def.namespace(), "FlashApp", handlerClass, List.copyOf(mwClasses));
        listeners.forEach(l -> l.onRoute(event));
    }

    private void emitWsEvent(String path, WebSocketEndpoint endpoint) {
        List<RouteListener> listeners = ctx.routeListeners();
        if (listeners.isEmpty()) return;
        RouteEvent event = new RouteEvent(HttpMethod.GET, path, "/", "FlashApp",
                endpoint.getClass(), List.of());
        listeners.forEach(l -> l.onRoute(event));
    }

    private static List<MiddlewareNode> concat(List<MiddlewareNode> global, List<MiddlewareNode> scope,
                                       List<MiddlewareNode> injected, List<MiddlewareNode> explicit) {
        int total = global.size() + scope.size() + injected.size() + explicit.size();
        if (total == 0) return List.of();
        List<MiddlewareNode> all = new ArrayList<>(total);
        all.addAll(global); all.addAll(scope); all.addAll(injected); all.addAll(explicit);
        return all;
    }
}
