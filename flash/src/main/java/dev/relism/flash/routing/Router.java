package dev.relism.flash.routing;

import dev.relism.flash.http.Handler;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.PathParams;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.bytes.FastPathViews;
import dev.relism.flash.websocket.Upgrade;
import dev.relism.fpr.core.ByteView;
import dev.relism.fpr.core.FastPathRouter;
import dev.relism.fpr.core.MatchResult;
import dev.relism.fpr.core.RouterBuilder;
import dev.relism.fpr.core.dsl.StringRouteParser;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;

/**
 * Every route of one app in a single byte-level state machine, and the one path a request takes
 * through it, whichever protocol carried it.
 *
 * <p>Matching runs over the method bytes followed by the path bytes. A WebSocket route lives under
 * a method of its own, {@code WS}, so a path can serve a page and a socket at once, and its
 * middleware runs before the handshake like any other route's. Built by the app when the server
 * starts; nothing is added afterwards.
 */
public final class Router {

    private static final byte[] WS = "WS".getBytes(StandardCharsets.US_ASCII);
    private final RouterBuilder<Handler> builder = new RouterBuilder<>();
    private final EnumSet<HttpMethod> methods = EnumSet.noneOf(HttpMethod.class);
    private FastPathRouter<ByteView, Handler> fsm;
    private String[] paramNames;
    private Handler fallback;
    private ErrorHandler errors;

    /** Adds a route; {@code method} null is a WebSocket route. */
    public void add(HttpMethod method, String path, Handler handler) {
        String target = method == HttpMethod.CONNECT ? PathUtils.sanitizeAuthority(path) : PathUtils.sanitize(path);
        if (method != null) methods.add(method);
        builder.add(StringRouteParser.parse((method == null ? "WS" : method.name()) + target), handler);
    }

    /**
     * Freezes the routes. {@code fallback} answers a request no route matched, already wrapped in the
     * app's own middleware so a CORS preflight or an access log sees it too.
     */
    public void compile(Handler fallback, ErrorHandler errors) {
        this.fallback = fallback;
        this.errors = errors;
        this.paramNames = builder.paramNames();
        this.fsm = builder.compile();
    }

    /** Per-connection matching state: created once per connection, reused by every request on it. */
    public Scratch newScratch() {
        return new Scratch();
    }

    /**
     * Runs {@code request} through its route and returns what to send: a {@link Response}, or an
     * {@link Upgrade} when a WebSocket route accepted it. {@code upgrade} says whether the request
     * asks for a WebSocket; without a socket route at that path it is served as a plain request.
     *
     * <p>A {@code HEAD} with no route of its own is served by the {@code GET} route (the writer
     * drops the body); a path that exists under other methods is a 405 naming them.
     */
    public Object dispatch(Request request, Response response, boolean upgrade, Scratch scratch) {
        HttpMethod method = request.method();
        Handler handler = upgrade ? match(WS, request, scratch) : null;
        if (handler == null) handler = route(request, scratch);
        if (handler == null && method == HttpMethod.HEAD) handler = match(HttpMethod.GET.getBytes(), request, scratch);
        if (handler == null) handler = fallback;

        Object result;
        try {
            result = handler.handle(request, response);
        } catch (Exception failure) {
            result = Errors.recover(errors, failure, request, response);
        }
        if (result instanceof Response || result instanceof Upgrade) return result;
        return result == null ? response : response.setBody(result);
    }

    /** The route for {@code request}'s own method and path, or null: matching alone, path parameters set. */
    public Handler route(Request request, Scratch scratch) {
        return match(request.method().getBytes(), request, scratch);
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
            if (candidate == request.method() || match(candidate.getBytes(), request, scratch) == null) continue;
            allow = allow == null ? new StringBuilder(candidate.name()) : allow.append(", ").append(candidate.name());
        }
        return allow == null ? null : allow.toString();
    }

    private Handler match(byte[] method, Request request, Scratch scratch) {
        MatchResult<Handler> result = scratch.result;
        result.reset();
        ByteView path = request.requestLine().getPath();
        scratch.view.reset(method, path);
        if (fsm.match(scratch.view, result) == FastPathRouter.NO_MATCH) return null;

        int count = result.paramCount();
        if (count > 0) {
            scratch.ensureParamCapacity(count);
            for (int i = 0; i < count; i++) {
                scratch.names[i] = paramNames[result.keyIdAt(i)];
                scratch.starts[i] = result.startAt(i) - method.length;
                scratch.lens[i] = result.lenAt(i);
            }
            scratch.params.reset(path, count);
            PathParams.inject(request, scratch.params);
        }
        return result.handler();
    }

    /** Matching state for one connection; its arrays grow to the connection's widest route and stay. */
    public static final class Scratch {
        final MatchResult<Handler> result = new MatchResult<>(32, 128);
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
