package dev.relism.extension;

import dev.relism.ServerHandle;
import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.AbstractRouter;
import dev.relism.routing.GlobalRouter;
import dev.relism.routing.Middleware;
import dev.relism.routing.Route;
import dev.relism.routing.RouteHandle;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Primary entry point for Flash. Creates and owns both the {@link GlobalRouter} and
 * the {@link HttpServer} (pure I/O transport). All route registration goes through
 * {@code FlashApp} or a {@link FlashScope} — never through the server or router directly.
 *
 * <p>Create via the static factories:
 * <pre>{@code
 * FlashApp app = FlashApp.create(8080);
 * FlashApp app = FlashApp.create(FlashConfiguration.builder().port(8080).build());
 * }</pre>
 *
 * <p>Install extensions, register routes, mount namespaces, then start:
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new JacksonExtension())
 *     .install(new OidcExtension(config))
 *     .get("/ping", (req, res) -> "pong")
 *     .get("/me",   (req, res) -> ClaimsHolder.user().email()).with(oidc.protect())
 *     .register(new HomePage())                 // @Route + annotation processors applied
 *     .scan("dev.example.handlers")             // classpath scan, no-arg constructors
 *     .mount("/api", scope -> {
 *         scope.register(new UserHandler());    // @Authenticated works here
 *         scope.get("/health", (req, res) -> "ok");
 *     })
 *     .start();
 * }</pre>
 *
 * <h3>Auto-flush</h3>
 * Calling any registration method returns a {@link RouteHandle}. Calling
 * {@link RouteHandle#with} is optional — if omitted, the route is registered
 * automatically before the next operation or at {@link #start()}. This means
 * trailing {@code .with()} calls are never required for routes with no middleware.
 */
public final class FlashApp implements FlashRegistrar {

    private final GlobalRouter     router;
    private final ServerHandle     server;
    private final ExtensionContext ctx = new ExtensionContext();

    /** The last returned RouteHandle not yet registered — auto-flushed before the next operation. */
    private RouteHandle<?> pending;

    /**
     * Global middlewares applied to every route, regardless of how it is registered
     * (lambda, class-based, or via {@link #scan}).
     * Accumulated via {@link #use}; applied outermost in the chain (before injected and
     * explicit middlewares).
     */
    private final List<Middleware> globalMiddlewares = new ArrayList<>();

    private FlashApp(FlashConfiguration config) {
        this.router = new GlobalRouter();
        try {
            this.server = ServerHandle.create(config, router);
        } catch (IOException e) {
            throw new RuntimeException("Failed to bind server socket on port " + config.getPort(), e);
        }
    }

    // ── Factories ─────────────────────────────────────────────────────────────

    /**
     * Creates a {@code FlashApp} listening on {@code port} with default configuration.
     *
     * @param port the TCP port to bind
     */
    public static FlashApp create(int port) {
        return create(FlashConfiguration.builder().port(port).build());
    }

    /**
     * Creates a {@code FlashApp} with full server configuration.
     *
     * @param config server configuration (port, host, buffer sizes, etc.)
     */
    public static FlashApp create(FlashConfiguration config) {
        return new FlashApp(config);
    }

    // ── Pending flush ─────────────────────────────────────────────────────────

    /**
     * Registers any pending route (from the previous {@code get/post/register} call)
     * with no middleware if it has not already been committed via {@link RouteHandle#with}.
     */
    private void flushPending() {
        if (pending != null) {
            pending.ensureRegistered();
            pending = null;
        }
    }

    private <P> RouteHandle<P> track(RouteHandle<P> handle) {
        flushPending();
        pending = handle;
        return handle;
    }

    // ── FlashRegistrar — extension installation ───────────────────────────────

    /**
     * Installs an extension. Extensions receive this {@code FlashApp} as a
     * {@link FlashRegistrar} so they can register routes and expose services.
     *
     * @param ext the extension to install
     * @return {@code this} for chaining
     */
    @Override
    public FlashApp install(FlashExtension ext) {
        flushPending();
        ext.install(this, ctx);
        return this;
    }

    // ── Global middleware ──────────────────────────────────────────────────────

    /**
     * Registers one or more global middlewares applied to <em>every</em> route on this app,
     * regardless of how the route is registered (lambda, class-based, or via {@link #scan}).
     *
     * <p>Global middlewares execute outermost — before annotation-injected middlewares
     * (e.g. {@code @Authenticated}) and before any explicit {@link RouteHandle#with} chain.
     * Execution order mirrors the declaration order: the first argument wraps everything else.
     *
     * <p>Must be called before {@link #start()}. Calling {@code use} after routes have already
     * been registered will not retroactively affect those routes.
     *
     * <pre>{@code
     * Middleware cors = next -> (req, res) -> {
     *     res.header("Access-Control-Allow-Origin", "*");
     *     if (req.method() == HttpMethod.OPTIONS) { res.status(204); return null; }
     *     return next.handle(req, res);
     * };
     *
     * FlashApp.create(8080)
     *     .use(cors)
     *     .scan("dev.example.handlers")
     *     .start();
     * }</pre>
     *
     * @param middlewares one or more middlewares to apply globally
     * @return {@code this} for chaining
     */
    public FlashApp use(Middleware... middlewares) {
        flushPending();
        globalMiddlewares.addAll(Arrays.asList(middlewares));
        return this;
    }

    /**
     * Prepends global middlewares to an explicit per-route array.
     * Returns {@code explicit} unchanged when no global middlewares have been registered
     * (zero-allocation fast path).
     */
    private Middleware[] withGlobal(Middleware[] explicit) {
        if (globalMiddlewares.isEmpty()) return explicit;
        return Stream.concat(globalMiddlewares.stream(), Arrays.stream(explicit))
                     .toArray(Middleware[]::new);
    }

    // ── FlashRegistrar — route registration ───────────────────────────────────

    @Override public RouteHandle<FlashApp> get    (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.GET,     path, h); }
    @Override public RouteHandle<FlashApp> post   (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.POST,    path, h); }
    @Override public RouteHandle<FlashApp> put    (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.PUT,     path, h); }
    @Override public RouteHandle<FlashApp> delete (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.DELETE,  path, h); }
    @Override public RouteHandle<FlashApp> patch  (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.PATCH,   path, h); }
    @Override public RouteHandle<FlashApp> options(String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.OPTIONS, path, h); }
    @Override public RouteHandle<FlashApp> head   (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.HEAD,    path, h); }
    @Override public RouteHandle<FlashApp> trace  (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.TRACE,   path, h); }
    @Override public RouteHandle<FlashApp> connect(String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.CONNECT, path, h); }
    @Override public RouteHandle<FlashApp> purge  (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.PURGE,   path, h); }

    private RouteHandle<FlashApp> routeLambda(HttpMethod method, String path, SimpleHandler.FunctionalHandler h) {
        return track(new RouteHandle<>(this, m -> {
            Middleware[] all = withGlobal(m);
            emit(method, path, null, List.of(), all);
            router.doRegister(method, path, h, all);
        }));
    }

    /**
     * Begins registration of a class-based handler. The class must carry a
     * {@link Route @Route} annotation. All registered {@link AnnotationProcessor}s
     * are run (e.g. to inject {@code @Authenticated} / {@code @RolesAllowed} middleware).
     * Injected middlewares are prepended outermost to any explicit ones passed via
     * {@link RouteHandle#with}.
     *
     * <p>Calling {@link RouteHandle#with} is optional — the route is registered
     * automatically before the next operation or at {@link #start()}.
     */
    @Override
    public RouteHandle<FlashApp> register(RequestHandler handler) {
        return track(new RouteHandle<>(this, explicit -> {
            List<Middleware> injected = ctx.processors().stream()
                    .flatMap(p -> p.process(handler.getClass()).stream())
                    .toList();
            Middleware[] all = Stream.concat(
                    globalMiddlewares.stream(),
                    Stream.concat(injected.stream(), Arrays.stream(explicit))
            ).toArray(Middleware[]::new);
            Route ann = handler.getClass().getAnnotation(Route.class);
            if (ann != null) emit(ann.method(), ann.path(), handler.getClass(), injected, withGlobal(explicit));
            router.doRegister(handler, all);
        }));
    }

    /**
     * Scans {@code packageName} for classes that extend {@link RequestHandler} and
     * carry {@link Route @Route}. Each is instantiated via its no-arg constructor,
     * run through annotation processors, and registered.
     *
     * <pre>{@code
     * FlashApp.create(8080)
     *     .install(new OidcExtension(config))
     *     .scan("dev.example.handlers");  // @Authenticated / @RolesAllowed auto-applied
     * }</pre>
     */
    @Override
    public FlashApp scan(String packageName) {
        flushPending();
        PackageScanner.findHandlers(packageName).forEach(cls -> register(instantiate(cls)).ensureRegistered());
        return this;
    }

    // ── Namespace mounting ────────────────────────────────────────────────────

    /**
     * Mounts a scoped sub-router under {@code namespace}. The {@code configure} consumer
     * receives a {@link FlashScope} that has its own child {@link ExtensionContext}
     * inheriting all parent services and annotation processors.
     *
     * <p>Routes registered on the scope automatically get the namespace prefix prepended.
     * Annotation processors (e.g. from OIDC) apply identically inside the scope.
     *
     * <pre>{@code
     * app.mount("/api", scope -> {
     *     scope.register(new UserHandler());    // @Authenticated works
     *     scope.get("/health", (req, res) -> "ok");
     *     scope.scan("dev.example.api");
     * });
     * }</pre>
     *
     * @param namespace the path prefix (e.g. {@code "/api"})
     * @param configure consumer that registers routes on the scope
     */
    public FlashApp mount(String namespace, Consumer<FlashScope> configure) {
        flushPending();
        FlashScope scope = new FlashScope(new dev.relism.routing.routers.fastpathrouter.FastPathRouterImpl(),
                namespace, ctx);
        configure.accept(scope);
        scope.flush();
        router.mount(namespace, scope.router());
        return this;
    }

    // ── FlashRegistrar — error handlers ──────────────────────────────────────

    @Override
    public FlashApp onException(AbstractRouter.ExceptionHandler handler) {
        flushPending();
        router.onException(handler);
        return this;
    }

    @Override
    public FlashApp onNotFound(SimpleHandler.FunctionalHandler handler) {
        flushPending();
        router.onNotFound(handler);
        return this;
    }

    // ── FlashRegistrar — context ──────────────────────────────────────────────

    @Override
    public ExtensionContext ctx() {
        return ctx;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Flushes any pending route registration and starts the HTTP server.
     *
     * @return a future that completes once the accept loop is running
     */
    public CompletableFuture<Void> start() {
        flushPending();
        return server.start();
    }

    /** Stops the HTTP server and closes all active connections. */
    public CompletableFuture<Void> stop() {
        return server.stop();
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private static RequestHandler instantiate(Class<?> cls) {
        try {
            return (RequestHandler) cls.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new RuntimeException("Failed to instantiate handler: " + cls.getName() +
                    " — ensure it has a public no-arg constructor", e);
        }
    }

    /**
     * Emits a {@link RouteEvent} to all registered {@link RouteListener}s.
     * No-op if no listener has been registered (fast empty-list check).
     * Called once per route at boot time — never on the request hot-path.
     */
    @SuppressWarnings("unchecked")
    private void emit(HttpMethod method, String path, Class<?> handlerClass,
                      List<Middleware> injected, Middleware[] explicit) {
        List<RouteListener> listeners = ctx.routeListeners();
        if (listeners.isEmpty()) return;
        Middleware[] routerMws = router.routerMiddlewares();
        List<Class<? extends Middleware>> chain = new ArrayList<>(routerMws.length + injected.size() + explicit.length);
        for (Middleware m : routerMws) chain.add((Class<? extends Middleware>) m.getClass());
        for (Middleware m : injected)  chain.add((Class<? extends Middleware>) m.getClass());
        for (Middleware m : explicit)  chain.add((Class<? extends Middleware>) m.getClass());
        RouteEvent event = new RouteEvent(method, path, router.getNamespace(),
                router.getClass().getSimpleName(), handlerClass, List.copyOf(chain));
        listeners.forEach(l -> l.onRoute(event));
    }
}
