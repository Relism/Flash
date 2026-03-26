package dev.relism.extension;

import dev.relism.HttpServer;
import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.AbstractRouter;
import dev.relism.routing.Middleware;
import dev.relism.routing.RouteHandle;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Primary entry point for working with Flash. Wraps {@link HttpServer} and
 * wires the extension layer.
 *
 * <p>The key addition over raw {@link HttpServer} is the annotation-aware
 * {@link #register} override: before delegating to Flash it runs all registered
 * {@link AnnotationProcessor}s and prepends any injected middlewares
 * (e.g. from {@code @RolesAllowed}) to the explicit ones.
 *
 * <p>Route registration is lazy: calling {@code get()}, {@code post()}, or
 * {@code register()} returns a {@link RouteHandle} that is not yet registered.
 * The route is registered automatically before the next operation or at
 * {@link #start()}. Call {@link RouteHandle#with} explicitly to add middleware:
 *
 * <pre>{@code
 * FlashApp.of(new HttpServer(config))
 *     .install(new JacksonExtension())
 *     .install(new OidcExtension(OidcConfig.fromEnv()))
 *     .install(new OpenApiExtension("/openapi"))
 *     .start();
 *
 * // No middleware — .with() is optional
 * app.get("/ping",  (req, res) -> "pong");
 * app.get("/hello", (req, res) -> "Hello");
 *
 * // With middleware
 * app.get("/me",    (req, res) -> ClaimsHolder.user().email()).with(oidc.protect());
 * app.get("/admin", (req, res) -> "secret").with(oidc.requireRole("admin"));
 *
 * // Class-based — @Authenticated / @RolesAllowed auto-injected, .with() optional
 * app.register(new HomePage());
 * app.register(new MePage());      // @Authenticated handled by annotation processor
 * app.register(new AdminPage());   // @RolesAllowed("admin") handled automatically
 *
 * app.start();
 * }</pre>
 */
public class FlashApp {

    private final HttpServer       server;
    private final ExtensionContext ctx = new ExtensionContext();

    /** The last returned RouteHandle that has not yet been registered. */
    private RouteHandle<?> pending;

    private FlashApp(HttpServer server) {
        this.server = server;
    }

    public static FlashApp of(HttpServer server) {
        return new FlashApp(server);
    }

    // ── Pending flush ─────────────────────────────────────────────────────────

    /**
     * Registers any pending route (returned by the previous {@code get/post/register}
     * call) with no middleware, if it has not already been registered via
     * {@link RouteHandle#with}.
     */
    private void flushPending() {
        if (pending != null) {
            pending.ensureRegistered();
            pending = null;
        }
    }

    private <P> RouteHandle<P> pending(RouteHandle<P> handle) {
        flushPending();
        pending = handle;
        return handle;
    }

    // ── Extension installation ────────────────────────────────────────────────

    public FlashApp install(FlashExtension ext) {
        flushPending();
        ext.install(this, ctx);
        return this;
    }

    /** Exposes the context so callers can retrieve services installed by extensions. */
    public ExtensionContext ctx() {
        return ctx;
    }

    // ── Handler registration (annotation-aware) ───────────────────────────────

    /**
     * Begins registration of a class-based handler. Before registering, all
     * {@link AnnotationProcessor}s are called (e.g. to inject OIDC middleware from
     * {@code @Authenticated} / {@code @RolesAllowed}). Their middlewares are prepended
     * outermost to any extra middlewares supplied via {@link RouteHandle#with}.
     *
     * <p>Calling {@link RouteHandle#with} is optional when no extra middleware is needed —
     * the route is registered automatically before the next operation or at {@link #start()}.
     *
     * <p>Middleware execution order: injected (from annotations) → explicit (.with()) → handler.
     */
    public RouteHandle<FlashApp> register(RequestHandler handler) {
        return pending(new RouteHandle<>(this, explicit -> {
            List<Middleware> injected = ctx.processors().stream()
                    .flatMap(p -> p.process(handler.getClass()).stream())
                    .toList();
            Middleware[] all = injected.isEmpty()
                    ? explicit
                    : Stream.concat(injected.stream(), Arrays.stream(explicit))
                            .toArray(Middleware[]::new);
            server.doRegister(handler, all);
        }));
    }

    // ── Lambda route registration ─────────────────────────────────────────────

    /**
     * Begins registration of a lambda route. Calling {@link RouteHandle#with} is
     * optional when no middleware is needed — the route is registered automatically
     * before the next operation or at {@link #start()}.
     *
     * <pre>{@code
     * app.get("/ping",  (req, res) -> "pong");                              // no middleware
     * app.get("/me",    (req, res) -> ClaimsHolder.user().email())
     *    .with(oidc.protect());                                             // with middleware
     * }</pre>
     */
    public RouteHandle<FlashApp> get    (String path, SimpleHandler.FunctionalHandler h) { return pending(new RouteHandle<>(this, m -> server.doRegister(HttpMethod.GET,     path, h, m))); }
    public RouteHandle<FlashApp> post   (String path, SimpleHandler.FunctionalHandler h) { return pending(new RouteHandle<>(this, m -> server.doRegister(HttpMethod.POST,    path, h, m))); }
    public RouteHandle<FlashApp> put    (String path, SimpleHandler.FunctionalHandler h) { return pending(new RouteHandle<>(this, m -> server.doRegister(HttpMethod.PUT,     path, h, m))); }
    public RouteHandle<FlashApp> delete (String path, SimpleHandler.FunctionalHandler h) { return pending(new RouteHandle<>(this, m -> server.doRegister(HttpMethod.DELETE,  path, h, m))); }
    public RouteHandle<FlashApp> patch  (String path, SimpleHandler.FunctionalHandler h) { return pending(new RouteHandle<>(this, m -> server.doRegister(HttpMethod.PATCH,   path, h, m))); }
    public RouteHandle<FlashApp> options(String path, SimpleHandler.FunctionalHandler h) { return pending(new RouteHandle<>(this, m -> server.doRegister(HttpMethod.OPTIONS, path, h, m))); }
    public RouteHandle<FlashApp> head   (String path, SimpleHandler.FunctionalHandler h) { return pending(new RouteHandle<>(this, m -> server.doRegister(HttpMethod.HEAD,    path, h, m))); }

    public FlashApp mount(String namespace, AbstractRouter router) {
        flushPending();
        server.mount(namespace, router);
        return this;
    }

    public FlashApp onException(AbstractRouter.ExceptionHandler handler) {
        flushPending();
        server.onException(handler);
        return this;
    }

    public FlashApp onNotFound(SimpleHandler.FunctionalHandler handler) {
        flushPending();
        server.onNotFound(handler);
        return this;
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    public CompletableFuture<Void> start() {
        flushPending();
        return server.start();
    }

    public CompletableFuture<Void> stop() {
        return server.stop();
    }

    /** Direct access to the underlying server for advanced use cases. */
    public HttpServer server() {
        return server;
    }
}
