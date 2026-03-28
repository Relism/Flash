package dev.relism.extension;

import dev.relism.http.HttpMethod;
import dev.relism.models.RequestHandler;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.AbstractRouter;
import dev.relism.routing.PathUtils;
import dev.relism.routing.Route;
import dev.relism.routing.RouteHandle;
import dev.relism.routing.Middleware;

import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;

/**
 * Scoped registration context for a mounted sub-router namespace.
 *
 * <p>Obtained via {@link FlashApp#mount(String, java.util.function.Consumer)}.
 * A scope has its own child {@link ExtensionContext} that inherits all services and
 * annotation processors from the parent app, so extensions like {@code @Authenticated}
 * and {@code @RolesAllowed} work identically inside a scope.
 *
 * <p>Extensions installed on a scope are scoped to that namespace and not visible
 * in the parent or sibling scopes.
 *
 * <pre>{@code
 * app.mount("/api", scope -> {
 *     scope.install(new RateLimitExtension());
 *     scope.register(new UserHandler());   // @Authenticated auto-injected
 *     scope.get("/health", (req, res) -> "ok");
 *     scope.scan("dev.example.api.handlers");
 * });
 * }</pre>
 */
public final class FlashScope implements FlashRegistrar {

    private final AbstractRouter  router;
    private final String          namespace;
    private final ExtensionContext ctx;

    /** Pending RouteHandle awaiting .with() — auto-flushed before each new registration. */
    private RouteHandle<?> pending;

    /**
     * Package-private — only {@link FlashApp} creates scopes.
     *
     * @param router    the sub-router that will receive routes registered on this scope
     * @param namespace the namespace prefix (e.g. {@code "/api"})
     * @param parentCtx the parent app's ExtensionContext — a child is created from it
     */
    FlashScope(AbstractRouter router, String namespace, ExtensionContext parentCtx) {
        this.router    = router;
        this.namespace = namespace;
        this.ctx       = parentCtx.child();
    }

    // ── Pending flush ─────────────────────────────────────────────────────────

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
     * Installs an extension scoped to this namespace.
     * The extension registers routes and services on this scope only.
     */
    @Override
    public FlashScope install(FlashExtension ext) {
        flushPending();
        ext.install(this, ctx);
        return this;
    }

    // ── FlashRegistrar — route registration ───────────────────────────────────

    @Override public RouteHandle<FlashScope> get    (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.GET,     path, h); }
    @Override public RouteHandle<FlashScope> post   (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.POST,    path, h); }
    @Override public RouteHandle<FlashScope> put    (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.PUT,     path, h); }
    @Override public RouteHandle<FlashScope> delete (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.DELETE,  path, h); }
    @Override public RouteHandle<FlashScope> patch  (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.PATCH,   path, h); }
    @Override public RouteHandle<FlashScope> options(String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.OPTIONS, path, h); }
    @Override public RouteHandle<FlashScope> head   (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.HEAD,    path, h); }
    @Override public RouteHandle<FlashScope> trace  (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.TRACE,   path, h); }
    @Override public RouteHandle<FlashScope> connect(String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.CONNECT, path, h); }
    @Override public RouteHandle<FlashScope> purge  (String path, SimpleHandler.FunctionalHandler h) { return routeLambda(HttpMethod.PURGE,   path, h); }

    private RouteHandle<FlashScope> routeLambda(HttpMethod method, String path, SimpleHandler.FunctionalHandler h) {
        return track(new RouteHandle<>(this, m -> {
            String full = ns(path);
            emit(method, full, null, List.of(), m);
            router.doRegister(method, full, h, m);
        }));
    }

    /**
     * Begins registration of a class-based handler. Annotation processors from the
     * parent app and any installed on this scope are applied. The {@link Route @Route}
     * path is prepended with this scope's namespace automatically.
     *
     * <p>Calling {@link RouteHandle#with} is optional — the route is registered
     * automatically before the next operation or when the scope consumer returns.
     */
    @Override
    public RouteHandle<FlashScope> register(RequestHandler handler) {
        return track(new RouteHandle<>(this, explicit -> {
            List<Middleware> injected = ctx.processors().stream()
                    .flatMap(p -> p.process(handler.getClass()).stream())
                    .toList();
            Middleware[] all = injected.isEmpty()
                    ? explicit
                    : Stream.concat(injected.stream(), Arrays.stream(explicit)).toArray(Middleware[]::new);
            Route annotation = handler.getClass().getAnnotation(Route.class);
            if (annotation != null) {
                String full = ns(annotation.path());
                emit(annotation.method(), full, handler.getClass(), injected, explicit);
                router.doRegister(annotation.method(), full, (RequestHandler) handler, all);
            }
        }));
    }

    /**
     * Scans {@code packageName} for {@link RequestHandler} subclasses annotated with
     * {@link Route @Route}. Each is instantiated via its no-arg constructor and registered
     * with this scope's namespace prefix and annotation processors applied.
     */
    @Override
    public FlashScope scan(String packageName) {
        flushPending();
        PackageScanner.findHandlers(packageName).forEach(cls -> register(instantiate(cls)).ensureRegistered());
        return this;
    }

    @Override
    public FlashScope onException(AbstractRouter.ExceptionHandler h) {
        flushPending();
        router.onException(h);
        return this;
    }

    @Override
    public FlashScope onNotFound(SimpleHandler.FunctionalHandler h) {
        flushPending();
        router.onNotFound(h);
        return this;
    }

    @Override
    public ExtensionContext ctx() {
        return ctx;
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    /** Ensures any pending route is registered when the scope consumer returns. */
    void flush() {
        flushPending();
    }

    /** Returns the sub-router for GlobalRouter to mount. */
    AbstractRouter router() {
        return router;
    }

    /** Prepends this scope's namespace to the given path. */
    private String ns(String path) {
        return namespace + PathUtils.sanitize(path);
    }

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
     * Emits a {@link RouteEvent} to all {@link RouteListener}s visible from this scope's context.
     * Parent-level listeners (registered on the app) are included via context inheritance.
     * No-op if no listener has been registered. Never called on the request hot-path.
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
        RouteEvent event = new RouteEvent(method, path, namespace,
                router.getClass().getSimpleName(), handlerClass, List.copyOf(chain));
        listeners.forEach(l -> l.onRoute(event));
    }
}
