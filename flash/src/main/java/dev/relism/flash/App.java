package dev.relism.flash;

import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.routing.ErrorHandler;
import dev.relism.flash.routing.Errors;
import dev.relism.flash.routing.ExchangeInterceptor;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareGraph;
import dev.relism.flash.routing.MiddlewareKey;
import dev.relism.flash.routing.MiddlewareNode;
import dev.relism.flash.routing.PathUtils;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.RouteHook;
import dev.relism.flash.routing.Router;
import dev.relism.flash.routing.Verb;
import dev.relism.flash.routing.Ws;
import dev.relism.flash.websocket.Upgrade;
import dev.relism.flash.websocket.WebSocketHandler;

import java.lang.annotation.Annotation;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * What you build: routes, middleware, modules and the services they need. A value with no port and
 * no thread; a {@link Server} runs it, and a test runs the very same one.
 *
 * <pre>{@code
 * static App api(Database db) {
 *     return App.create()
 *             .install(new JsonModule(), new DataModule(db))
 *             .add(Users.class)
 *             .scan("com.acme.api")
 *             .get("/healthz", (req, res) -> "ok");
 * }
 *
 * Server.start(api(db), ServerConfig.builder().port(8080).build());
 * }</pre>
 *
 * <p>Every app has its own services, so several apps live in one process without sharing anything
 * they were not handed.
 */
public final class App {

    private final Shared shared;
    private final App parent;
    private final String prefix;
    private final List<Middleware> middleware = new ArrayList<>();

    private App(Shared shared, App parent, String prefix) {
        this.shared = shared;
        this.parent = parent;
        this.prefix = prefix;
    }

    public static App create() {
        return new App(new Shared(), null, "");
    }

    // -- Routes ---------------------------------------------------------------

    public App get(String path, Handler handler, Middleware... middleware) { return route(HttpMethod.GET, path, handler, middleware); }
    public App post(String path, Handler handler, Middleware... middleware) { return route(HttpMethod.POST, path, handler, middleware); }
    public App put(String path, Handler handler, Middleware... middleware) { return route(HttpMethod.PUT, path, handler, middleware); }
    public App patch(String path, Handler handler, Middleware... middleware) { return route(HttpMethod.PATCH, path, handler, middleware); }
    public App delete(String path, Handler handler, Middleware... middleware) { return route(HttpMethod.DELETE, path, handler, middleware); }

    /** {@code handler} under every method: for handlers indifferent to it, a proxy above all. */
    public App any(String path, Handler handler, Middleware... middleware) {
        for (HttpMethod method : HttpMethod.values()) route(method, path, handler, middleware);
        return this;
    }

    public App route(HttpMethod method, String path, Handler handler, Middleware... middleware) {
        return declare(method, path, Objects.requireNonNull(handler, "handler"), middleware);
    }

    /** A handler class the app builds, with its services as constructor arguments. */
    public App route(HttpMethod method, String path, Class<? extends Handler> handler, Middleware... middleware) {
        return declare(method, path, Objects.requireNonNull(handler, "handler"), middleware);
    }

    public App ws(String path, WebSocketHandler handler, Middleware... middleware) {
        return declare(null, path, Objects.requireNonNull(handler, "handler"), middleware);
    }

    public App ws(String path, Class<? extends WebSocketHandler> handler, Middleware... middleware) {
        return declare(null, path, Objects.requireNonNull(handler, "handler"), middleware);
    }

    /**
     * Every concrete {@link Handler} in {@code packageName} (and below) carrying a routing annotation
     * ({@code @GET}, {@code @POST}, …), and every {@link WebSocketHandler} carrying {@code @Ws}. A
     * package with none of them fails the boot.
     */
    public App scan(String packageName) {
        int before = shared.routes.size();
        for (Class<?> type : Scanner.classes(packageName)) {
            if (Modifier.isAbstract(type.getModifiers())) continue;
            Ws ws = type.getAnnotation(Ws.class);
            if (ws != null && WebSocketHandler.class.isAssignableFrom(type)) {
                declare(null, ws.value(), type, new Middleware[0]);
                continue;
            }
            Annotation verb = verbOf(type);
            if (verb != null && Handler.class.isAssignableFrom(type))
                declare(verb.annotationType().getAnnotation(Verb.class).value(), pathOf(verb), type, new Middleware[0]);
        }
        if (shared.routes.size() == before)
            throw new IllegalStateException("scan(\"" + packageName + "\") found no handler with a routing annotation");
        return this;
    }

    /** Middleware around every route of this app, or of this mount. */
    public App use(Middleware... middleware) {
        this.middleware.addAll(List.of(middleware));
        return this;
    }

    /** Routes under {@code prefix}, with middleware of their own on top of this app's. Same services. */
    public App mount(String prefix, Consumer<App> routes) {
        routes.accept(new App(shared, this, PathUtils.join(this.prefix, PathUtils.sanitize(prefix))));
        return this;
    }

    public App install(Module... modules) {
        for (Module module : modules) module.install(this);
        return this;
    }

    // -- Services -------------------------------------------------------------

    /** A service the app builds by its constructor. Adding the same class twice adds it once. */
    public <T> App add(Class<T> type) {
        shared.services.add(type, type);
        return this;
    }

    /** {@code implementation}, built by its constructor, as a {@code type}. */
    public <T> App add(Class<T> type, Class<? extends T> implementation) {
        shared.services.add(type, implementation);
        return this;
    }

    /** A service already built. The app owns it from now on: if it is {@link AutoCloseable}, stopping closes it. */
    public <T> App add(Class<T> type, T instance) {
        shared.services.add(type, Objects.requireNonNull(instance, "instance"));
        return this;
    }

    /** Swaps every {@code type} for {@code instance}. Meant for tests: a fake for the real thing. */
    public <T> App replace(Class<T> type, T instance) {
        shared.services.replace(type, Objects.requireNonNull(instance, "instance"));
        return this;
    }

    /** The app's services. Readable once the server has started. */
    public Services services() {
        return shared.services;
    }

    /** Work to run once every service is built and before the first connection is accepted. */
    public App onStart(Consumer<Services> work) {
        shared.onStart.add(work);
        return this;
    }

    public App onError(ErrorHandler handler) {
        shared.errors = handler;
        return this;
    }

    public App onNotFound(Handler handler) {
        shared.notFound = handler;
        return this;
    }

    // -- Boot, through Server ----------------------------------------------------

    /** Builds every service and handler and freezes the routes. */
    /** Builds every route; {@code config} becomes a service, so what the app builds can read it. */
    Router compile(ServerConfig config) {
        if (shared.compiled) throw new IllegalStateException("An app runs on one server");
        shared.compiled = true;
        boolean dev = config.isDev();
        Services services = shared.services;
        services.add(ServerConfig.class, config);
        services.start();
        List<RouteHook> hooks = services.all(RouteHook.class);
        List<ExchangeInterceptor> interceptors = services.all(ExchangeInterceptor.class);
        ErrorHandler errors = shared.errors != null ? shared.errors : Errors.handler(dev);
        Router router = new Router();

        for (Declared route : shared.routes) {
            Class<?> type = route.handler instanceof Class<?> c ? c : null;
            Object built = type != null ? services.create(type) : route.handler;
            Handler handler;
            if (built instanceof WebSocketHandler socket) {
                socket.onInit();
                Upgrade upgrade = new Upgrade(socket);
                handler = (request, response) -> upgrade;
            } else {
                handler = (Handler) built;
                handler.onInit();
            }

            List<MiddlewareNode> nodes = new ArrayList<>();
            route.scope.collect(nodes);
            if (type != null) for (RouteHook hook : hooks) nodes.addAll(hook.middleware(type));
            for (Middleware explicit : route.middleware) nodes.add(inline(nodes.size(), explicit));
            String label = (route.method == null ? "WS" : route.method.name()) + ' ' + route.path;
            Middleware[] chain = MiddlewareGraph.order(label, nodes);

            Route compiled = new Route(route.method == null ? HttpMethod.GET : route.method, route.path,
                    route.method == null, type, classesOf(chain));
            handler = MiddlewareGraph.compose(handler, chain);
            if (!interceptors.isEmpty()) handler = intercept(compiled, Errors.boundary(handler, errors), interceptors);
            router.add(route.method, route.path, handler);
            for (RouteHook hook : hooks) hook.routed(compiled);
        }

        Handler notFound = shared.notFound != null ? shared.notFound : Errors.notFound(dev);
        Handler miss = (request, response) -> {
            String allow = router.allowed(request);
            if (allow != null) throw HttpException.methodNotAllowed(allow);
            return notFound.handle(request, response);
        };
        List<MiddlewareNode> nodes = new ArrayList<>();
        collect(nodes);
        Handler fallback = MiddlewareGraph.compose(miss, MiddlewareGraph.order("fallback", nodes));
        if (!interceptors.isEmpty()) fallback = interceptFallback(Errors.boundary(fallback, errors), interceptors);
        router.compile(fallback, errors);
        return router;
    }

    void started() {
        for (Consumer<Services> work : shared.onStart) work.accept(shared.services);
    }

    // -- Internals ---------------------------------------------------------------

    private App declare(HttpMethod method, String path, Object handler, Middleware[] middleware) {
        if (shared.compiled) throw new IllegalStateException("Routes are declared before the server starts");
        shared.routes.add(new Declared(this, method, PathUtils.join(prefix, PathUtils.sanitize(path)), handler, middleware.clone()));
        return this;
    }

    /** This app's middleware and every enclosing app's, outermost first; read at compile time, so order of calls never matters. */
    private void collect(List<MiddlewareNode> nodes) {
        if (parent != null) parent.collect(nodes);
        for (Middleware each : middleware) nodes.add(inline(nodes.size(), each));
    }

    private static MiddlewareNode inline(int position, Middleware middleware) {
        return MiddlewareNode.of(MiddlewareKey.of("flash.inline." + position), middleware);
    }

    private static Handler intercept(Route route, Handler exchange, List<ExchangeInterceptor> interceptors) {
        for (int i = interceptors.size() - 1; i >= 0; i--) exchange = interceptors.get(i).wrap(route, exchange);
        return exchange;
    }

    private static Handler interceptFallback(Handler exchange, List<ExchangeInterceptor> interceptors) {
        for (int i = interceptors.size() - 1; i >= 0; i--) exchange = interceptors.get(i).wrapFallback(exchange);
        return exchange;
    }

    @SuppressWarnings("unchecked")
    private static List<Class<? extends Middleware>> classesOf(Middleware[] chain) {
        List<Class<? extends Middleware>> classes = new ArrayList<>(chain.length);
        for (Middleware each : chain) classes.add((Class<? extends Middleware>) each.getClass());
        return List.copyOf(classes);
    }

    private static Annotation verbOf(Class<?> type) {
        Annotation found = null;
        for (Annotation annotation : type.getAnnotations()) {
            if (!annotation.annotationType().isAnnotationPresent(Verb.class)) continue;
            if (found != null)
                throw new IllegalStateException(type.getName() + " carries two routing annotations: it answers one route");
            found = annotation;
        }
        return found;
    }

    private static String pathOf(Annotation verb) {
        try {
            return (String) verb.annotationType().getMethod("value").invoke(verb);
        } catch (ReflectiveOperationException broken) {
            throw new IllegalStateException(verb.annotationType().getName() + " needs a String value()", broken);
        }
    }

    private record Declared(App scope, HttpMethod method, String path, Object handler, Middleware[] middleware) {}

    /** What the root app and its mounts share. */
    private static final class Shared {
        final Services services = new Services();
        final List<Declared> routes = new ArrayList<>();
        final List<Consumer<Services>> onStart = new ArrayList<>();
        ErrorHandler errors;
        Handler notFound;
        boolean compiled;
    }
}
