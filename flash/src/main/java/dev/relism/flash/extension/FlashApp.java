package dev.relism.flash.extension;

import dev.relism.flash.Flash;
import dev.relism.flash.ServerHandle;
import dev.relism.flash.exceptions.InitializationException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.SimpleHandler;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathRouterImpl;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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
 *   <li>Extensions sorted by {@link FlashExtension#priority()} — lower first.</li>
 *   <li>All {@link FlashExtension#provide} — register services, processors, listeners.</li>
 *   <li>{@link FlashContext#resolveAll()} — topo-sort, cycle detection.</li>
 *   <li>All {@link FlashExtension#routes} — routes registered, services available.</li>
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
    private final ServerHandle          server;
    private final FlashContext          ctx        = new FlashContext();
    private final List<FlashExtension>  extensions        = new ArrayList<>();
    private final List<Middleware>      globalMiddlewares = new ArrayList<>();
    private final List<RouteDefinition> deferredRoutes    = new ArrayList<>();
    private int port;

    private FlashApp(FlashConfiguration config) {
        try {
            this.server = ServerHandle.create(config, router);
            this.port   = config.getPort();
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
     * Registers an extension for two-phase installation at startup.
     * Install order is irrelevant — all {@link FlashExtension#provide} calls complete
     * before any {@link FlashExtension#routes} call begins.
     * Extensions are sorted by {@link FlashExtension#priority()} before execution.
     */
    public FlashApp install(FlashExtension ext) {
        extensions.add(ext);
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
        if (Flash.DEV) log.info("[Flash] Started (dev mode): listening on port " + port);
        return this;
    }

    /**
     * Boots the server and blocks the calling thread until the server is stopped.
     * Suitable for the end of {@code main()} — no need to keep the JVM alive manually.
     */
    public void startAndBlock() {
        boot();
        if (Flash.DEV) log.info("[Flash] Started (dev mode): listening on port " + port);
        server.startAndBlock();
    }

    public CompletableFuture<Void> stop() { return server.stop(); }

    // ── FlashRegistrar impl ───────────────────────────────────────────────────

    @Override
    protected void addRoute(HttpMethod method, String path, RequestHandler handler, List<Middleware> mw) {
        deferredRoutes.add(new RouteDefinition(
                method, path, handler, List.of(), mw,
                !(handler instanceof SimpleHandler), ctx, "/"));
    }

    @Override
    protected void addMiddleware(Middleware mw) { globalMiddlewares.add(mw); }

    // ── Boot ─────────────────────────────────────────────────────────────────

    private void boot() {
        extensions.sort(Comparator.comparingInt(FlashExtension::priority));
        extensions.forEach(e -> e.provide(ctx));
        ctx.resolveAll();
        extensions.forEach(e -> e.routes(this, ctx));
        compile();
    }

    // ── Compilation ──────────────────────────────────────────────────────────

    private static final Middleware[] EMPTY_MW = new Middleware[0];

    /** Middleware chain order per route: Global → Scope → Annotation → Explicit. */
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
            Middleware[] all = concat(globalMiddlewares, def.scopeMiddlewares(), injected, def.explicitMiddlewares());
            emitEvent(def, all);
            router.doRegister(def.method(), def.path(), def.handler(), all);
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

    private static Middleware[] concat(List<Middleware> global, List<Middleware> scope,
                                       List<Middleware> injected, List<Middleware> explicit) {
        int total = global.size() + scope.size() + injected.size() + explicit.size();
        if (total == 0) return EMPTY_MW;
        Middleware[] all = new Middleware[total];
        int i = 0;
        for (Middleware m : global)   all[i++] = m;
        for (Middleware m : scope)    all[i++] = m;
        for (Middleware m : injected) all[i++] = m;
        for (Middleware m : explicit) all[i++] = m;
        return all;
    }
}
