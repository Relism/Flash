package dev.relism.flash.routing;

import dev.relism.flash.http.Exchange;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.PathParams;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.bytes.FastPathViews;
import dev.relism.fpr.core.ByteView;
import dev.relism.fpr.core.FastPathRouter;
import dev.relism.fpr.core.MatchResult;
import dev.relism.fpr.core.RoutePattern;
import dev.relism.fpr.core.RouterBuilder;
import dev.relism.fpr.core.dsl.StringRouteParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.nio.charset.StandardCharsets;

/**
 * Every route of one app in a single byte-level state machine, and the one path a request takes
 * through it, whichever protocol carried it.
 *
 * <p>Matching runs over the method token, as it arrived, followed by the path bytes. A WebSocket
 * route lives under a method of its own, {@code WS}, so a path can serve a page and a socket at
 * once, and its middleware runs before the handshake like any other route's. A route for every
 * method lives under {@code *}, which matches any token; a route under a method of its own wins
 * over it. A path that ends in {@code /**} names what that matched {@code **}. Built by the app when
 * the server starts; nothing is added afterwards.
 */
public final class Router {

    private static final ByteView WS = new FastPathViews.SocketByteView("WS".getBytes(StandardCharsets.US_ASCII));
    private static final ByteView[] METHODS = Arrays.stream(HttpMethod.values())
            .map(method -> new FastPathViews.SocketByteView(method.getBytes())).toArray(ByteView[]::new);
    private final RouterBuilder<Next> builder = new RouterBuilder<>();
    private final EnumSet<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
    private final Set<Next> everyMethod = Collections.newSetFromMap(new IdentityHashMap<>());
    private FastPathRouter<ByteView, Next> fsm;
    private String[] paramNames;
    private Next fallback;

    /** Adds a route; {@code method == null} is the WebSocket route key. */
    public void add(HttpMethod method, String path, Next handler) {
        String target = method == HttpMethod.CONNECT ? PathUtils.sanitizeAuthority(path) : PathUtils.sanitize(path);
        if (method != null) methods.add(method);
        builder.add(pattern((method == null ? "WS" : method.name()) + target), handler);
    }

    /** Adds a route for every method, standard or not. */
    public void any(String path, Next handler) {
        everyMethod.add(handler);
        builder.add(pattern("*" + PathUtils.sanitize(path)), handler);
    }

    /** The key as fpr parses it, with a trailing {@code **} named {@code "**"} so a handler can read the rest. */
    private static RoutePattern pattern(String key) {
        RoutePattern parsed = StringRouteParser.parse(key);
        if (!key.endsWith("/**")) return parsed;
        List<RoutePattern.Segment> segments = new ArrayList<>(parsed.segments());
        segments.set(segments.size() - 1, RoutePattern.catchAll("**"));
        return RoutePattern.fromSegments(segments);
    }

    /**
     * Freezes the routes. {@code fallback} answers a request no route matched, already wrapped in the
     * app's own middleware so a CORS preflight or an access log sees it too.
     */
    public void compile(Next fallback) {
        this.fallback = fallback;
        this.paramNames = builder.paramNames();
        this.fsm = builder.compile();
    }

    /** Per-connection matching state: created once per connection, reused by every request on it. */
    public Scratch newScratch() {
        return new Scratch();
    }

    /**
     * Runs {@code request} through its route and returns the response to send. A response written
     * as it goes has been written by then.
     *
     * <p>A {@code HEAD} with no route of its own is served by the {@code GET} route (the writer
     * drops the body), also when a route for every method covers the path; a path that exists
     * under other methods is a 405 naming them.
     */
    public Response dispatch(Exchange exchange, Scratch scratch) throws Exception {
        Request request = exchange.request();
        HttpMethod method = request.method();
        Next handler = websocket(request) ? match(WS, request, scratch) : null;
        if (handler == null) handler = route(request, scratch);
        if (method == HttpMethod.HEAD && (handler == null || everyMethod.contains(handler))) {
            Next get = match(METHODS[HttpMethod.GET.ordinal()], request, scratch);
            if (get != null) handler = get;
        }
        if (handler == null) handler = fallback;

        handler.handle(exchange);
        return exchange.response();
    }

    /** The route for {@code request}'s own method and path, or null: matching alone, path parameters set. */
    public Next route(Request request, Scratch scratch) {
        return match(request.requestLine().getMethodToken(), request, scratch);
    }

    /**
     * The methods {@code request}'s path answers to, as an {@code Allow} value, or null when it
     * answers to none. Only reached when nothing matched.
     */
    public String allowed(Request request) {
        // ponytail: a fresh scratch per miss; a miss is already the slow path
        Scratch scratch = new Scratch();
        StringBuilder allow = null;
        for (HttpMethod candidate : methods) {
            if (candidate == request.method() || match(METHODS[candidate.ordinal()], request, scratch) == null) continue;
            allow = allow == null ? new StringBuilder(candidate.name()) : allow.append(", ").append(candidate.name());
        }
        return allow == null ? null : allow.toString();
    }

    private Next match(ByteView method, Request request, Scratch scratch) {
        MatchResult<Next> result = scratch.result;
        result.reset();
        ByteView path = request.requestLine().getPath();
        scratch.view.reset(method, path);
        if (fsm.match(scratch.view, result) == FastPathRouter.NO_MATCH) return null;

        int count = result.paramCount();
        if (count > 0) {
            scratch.ensureParamCapacity(count);
            for (int i = 0; i < count; i++) {
                scratch.names[i] = paramNames[result.keyIdAt(i)];
                scratch.starts[i] = result.startAt(i) - method.length();
                scratch.lens[i] = result.lenAt(i);
            }
            scratch.params.reset(path, count);
            PathParams.inject(request, scratch.params);
        }
        return result.handler();
    }

    private static boolean websocket(Request request) {
        if ("websocket".equals(request.connectProtocol())) return true;
        ByteView value = request.headerView("Upgrade");
        if (value == null || value.length() != 9) return false;
        String expected = "websocket";
        for (int i = 0; i < value.length(); i++) {
            int actual = value.byteAt(i) & 0xff;
            if ((actual | 0x20) != expected.charAt(i)) return false;
        }
        return true;
    }

    /** Matching state for one connection; its arrays grow to the connection's widest route and stay. */
    public static final class Scratch {
        final MatchResult<Next> result = new MatchResult<>(32, 128);
        final FastPathViews.MethodPathByteView view = new FastPathViews.MethodPathByteView();
        String[] names = new String[8];
        int[] starts = new int[8];
        int[] lens = new int[8];
        PathParams params = new PathParams(names, starts, lens);

        private Scratch() {}

        void ensureParamCapacity(int count) {
            if (count <= names.length) return;
            int grown = names.length;
            while (grown < count) grown *= 2;
            names = Arrays.copyOf(names, grown);
            starts = Arrays.copyOf(starts, grown);
            lens = Arrays.copyOf(lens, grown);
            params = new PathParams(names, starts, lens);
        }
    }
}
