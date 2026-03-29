package dev.relism.extension;

import dev.relism.ServerHandle;
import dev.relism.exceptions.InitializationException;
import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.AbstractRouter;
import dev.relism.routing.Middleware;
import dev.relism.routing.Route;
import dev.relism.routing.RouteHandle;
import dev.relism.routing.routers.fastpathrouter.FastPathRouterImpl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Single entry point for Flash. Owns one flat {@link FastPathRouterImpl} —
 * all routes (app-level and scoped) compile into a single FSM at {@link #start()}.
 *
 * <h3>Deferred routing</h3>
 * Routes accumulate during the builder phase. At {@code start()}:
 * <ol>
 *   <li>Global middlewares are prepended to every route</li>
 *   <li>Annotation processors run for class-based handlers</li>
 *   <li>Handlers are bound to the {@link FlashContext}</li>
 *   <li>All routes compile into one FSM — zero prefix scanning at runtime</li>
 * </ol>
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new JacksonExtension())
 *     .use(cors)
 *     .get("/ping", (req, res) -> "pong")
 *     .scan("dev.example.handlers")
 *     .mount("/api", scope -> scope.get("/health", (req, res) -> "ok"))
 *     .start();
 * }</pre>
 */
public final class FlashApp implements FlashRegistrar {

    private final AbstractRouter          router = new FastPathRouterImpl();
    private final ServerHandle            server;
    private final FlashContext         ctx    = new FlashContext();
    private final List<Middleware>         globalMiddlewares = new ArrayList<>();
    private final List<RouteDefinition>   deferredRoutes    = new ArrayList<>();

    private RouteHandle<?> pending;

    private FlashApp(FlashConfiguration config) {
        try {
            this.server = ServerHandle.create(config, router);
        } catch (IOException e) {
            throw new InitializationException("Failed to bind on port " + config.getPort(), e);
        }
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    public static FlashApp create(int port) {
        return create(FlashConfiguration.builder().port(port).build());
    }

    public static FlashApp create(FlashConfiguration config) {
        return new FlashApp(config);
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

    // ── Extension installation ────────────────────────────────────────────────

    @Override
    public FlashApp install(FlashExtension ext) {
        flushPending();
        ext.install(this, ctx);
        return this;
    }

    // ── Global middleware ─────────────────────────────────────────────────────

    /**
     * Registers global middlewares applied to <em>every</em> route — including
     * routes registered before this call and routes inside mounted scopes.
     * Order-independent: resolved at {@link #start()}.
     */
    public FlashApp use(Middleware... middlewares) {
        flushPending();
        globalMiddlewares.addAll(Arrays.asList(middlewares));
        return this;
    }

    // ── Route registration (deferred) ─────────────────────────────────────────

    @Override public RouteHandle<FlashApp> get    (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.GET,     path, h); }
    @Override public RouteHandle<FlashApp> post   (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.POST,    path, h); }
    @Override public RouteHandle<FlashApp> put    (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.PUT,     path, h); }
    @Override public RouteHandle<FlashApp> delete (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.DELETE,  path, h); }
    @Override public RouteHandle<FlashApp> patch  (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.PATCH,   path, h); }
    @Override public RouteHandle<FlashApp> options(String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.OPTIONS, path, h); }
    @Override public RouteHandle<FlashApp> head   (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.HEAD,    path, h); }
    @Override public RouteHandle<FlashApp> trace  (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.TRACE,   path, h); }
    @Override public RouteHandle<FlashApp> connect(String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.CONNECT, path, h); }
    @Override public RouteHandle<FlashApp> purge  (String path, SimpleHandler.FunctionalHandler h) { return lambda(HttpMethod.PURGE,   path, h); }

    private static final Middleware[] NO_MW = new Middleware[0];

    private RouteHandle<FlashApp> lambda(HttpMethod method, String path, SimpleHandler.FunctionalHandler h) {
        return track(new RouteHandle<>(this, mw ->
                deferredRoutes.add(new RouteDefinition(method, path, new SimpleHandler(h), NO_MW, mw, false, ctx, "/"))));
    }

    /**
     * Registers a class-based handler annotated with {@link Route @Route}.
     * App-level only — not part of {@link FlashRegistrar}.
     */
    public RouteHandle<FlashApp> register(RequestHandler handler) {
        Route ann = handler.getClass().getAnnotation(Route.class);
        if (ann == null)
            throw new InitializationException(
                    handler.getClass().getName() + " is missing @Route");
        return track(new RouteHandle<>(this, mw ->
                deferredRoutes.add(new RouteDefinition(ann.method(), ann.path(), handler, NO_MW, mw, true, ctx, "/"))));
    }

    @Override
    public FlashApp scan(String packageName) {
        flushPending();
        PackageScanner.findHandlers(packageName).forEach(cls ->
                register(instantiate(cls)).ensureRegistered());
        return this;
    }

    // ── Namespace mounting (syntactic sugar — routes go into same flat router) ─

    /**
     * Mounts a scoped group of routes under {@code namespace}. The scope is a
     * pure builder — it prepends the namespace to each path and collects
     * {@link RouteDefinition}s that merge into this app's single flat router.
     */
    public FlashApp mount(String namespace, Consumer<FlashScope> configure) {
        flushPending();
        FlashScope scope = new FlashScope(namespace, ctx);
        configure.accept(scope);
        scope.flush();
        deferredRoutes.addAll(scope.routes());
        return this;
    }

    // ── Error handlers ────────────────────────────────────────────────────────

    public FlashApp onException(AbstractRouter.ExceptionHandler handler) {
        flushPending();
        router.onException(handler);
        return this;
    }

    public FlashApp onNotFound(SimpleHandler.FunctionalHandler handler) {
        flushPending();
        router.onNotFound(handler);
        return this;
    }

    @Override
    public FlashContext ctx() { return ctx; }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Compiles all deferred routes into the flat FSM router, then starts
     * the HTTP transport. One pass, one router, zero prefix scanning.
     */
    public CompletableFuture<Void> start() {
        flushPending();
        compile();
        return server.start();
    }

    public CompletableFuture<Void> stop() { return server.stop(); }

    // ── Compilation ──────────────────────────────────────────────────────────

    /**
     * Compiles all deferred routes. Middleware chain order:
     * Global → Scope → Annotation (class-based only) → Explicit (.with).
     */
    private void compile() {
        for (RouteDefinition def : deferredRoutes) {
            List<Middleware> injected;
            if (def.classBasedHandler()) {
                injected = def.ctx().processors().stream()
                        .flatMap(p -> p.process(def.handler().getClass()).stream())
                        .toList();
                def.handler().bind(def.ctx());
            } else {
                injected = List.of();
            }

            Middleware[] all = concat(globalMiddlewares, def.scopeMiddlewares(),
                    injected, def.explicitMiddlewares());
            emitEvent(def, all);
            router.doRegister(def.method(), def.path(), def.handler(), all);
        }
    }

    @SuppressWarnings("unchecked")
    private void emitEvent(RouteDefinition def, Middleware[] allMiddlewares) {
        List<RouteListener> listeners = def.ctx().routeListeners();
        if (listeners.isEmpty()) return;

        List<Class<? extends Middleware>> chain = new ArrayList<>(allMiddlewares.length);
        for (Middleware m : allMiddlewares)
            chain.add((Class<? extends Middleware>) m.getClass());

        Class<?> handlerClass = def.classBasedHandler() ? def.handler().getClass() : null;
        RouteEvent event = new RouteEvent(def.method(), def.path(), def.namespace(),
                "FlashApp", handlerClass, List.copyOf(chain));
        listeners.forEach(l -> l.onRoute(event));
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private static Middleware[] concat(List<Middleware> global, Middleware[] scope,
                                       List<Middleware> injected, Middleware[] explicit) {
        int total = global.size() + scope.length + injected.size() + explicit.length;
        if (total == 0) return NO_MW;
        if (total == explicit.length && scope.length == 0) return explicit;
        Middleware[] all = new Middleware[total];
        int i = 0;
        for (Middleware m : global)   all[i++] = m;
        for (Middleware m : scope)    all[i++] = m;
        for (Middleware m : injected) all[i++] = m;
        System.arraycopy(explicit, 0, all, i, explicit.length);
        return all;
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
