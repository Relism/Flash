package dev.relism.flash.routing.routers.fastpathrouter;

import dev.relism.fpr.core.ByteView;
import dev.relism.fpr.core.FastPathRouter;
import dev.relism.fpr.core.MatchResult;
import dev.relism.fpr.core.RouterBuilder;
import dev.relism.fpr.core.dsl.StringRouteParser;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.routing.AbstractWsRouter;
import dev.relism.flash.websocket.WebSocketHandler;

/**
 * WebSocket-upgrade counterpart of {@link FastPathRouterImpl} — same {@code fpr-core} matching
 * via {@link #newScratch} in place of the {@code ThreadLocal}s this class used to hold). Unlike
 * registry entry names {@code FastPathRouterImpl.route} specifically) and still allocates a
 * fresh {@code PathParams} per matched, parametric WebSocket upgrade — WebSocket upgrades are
 * inherently rare relative to ordinary requests (one per connection, not one per message), so
 * this was not flagged as a hot-path allocation concern.
 */
public final class FastPathWsRouterImpl extends AbstractWsRouter {

    private final RouterBuilder<WebSocketHandler>               builder = new RouterBuilder<>();
    private volatile FastPathRouter<ByteView, WebSocketHandler> router;
    private          String[]                                   cachedParamNames;

    /** Per-connection reusable matching state. */
    static final class RouteScratch {
        final MatchResult<WebSocketHandler> matchResult = new MatchResult<>(32, 128);
        final FastPathViews.MethodPathByteView combinedView = new FastPathViews.MethodPathByteView();
    }

    @Override
    public Object newScratch() {
        return new RouteScratch();
    }

    @Override
    protected AbstractWsRouter addRoute(HttpMethod method, String path, WebSocketHandler handler) {
        builder.add(StringRouteParser.parse(method.name() + path), handler);
        this.router = null;
        return this;
    }

    @Override
    public WebSocketHandler route(Request request, Object scratchObj) {
        ensureCompiled();
        RouteScratch scratch = (RouteScratch) scratchObj;

        MatchResult<WebSocketHandler> result = scratch.matchResult;
        result.reset();

        HttpMethod method = request.getRequestLine().getMethod();
        ByteView pathView = request.getRequestLine().getPath();
        FastPathViews.MethodPathByteView combined = scratch.combinedView;
        combined.reset(method.getBytes(), pathView);

        int labelId = router.match(combined, result);
        if (labelId == FastPathRouter.NO_MATCH) return null;

        if (result.paramCount() > 0) {
            setPathParams(request, result, cachedParamNames, method.getBytes().length);
        }

        return result.handler();
    }

    private void ensureCompiled() {
        if (router == null) {
            synchronized (this) {
                if (router == null) {
                    cachedParamNames = builder.paramNames();
                    router = builder.compile();
                }
            }
        }
    }

    @Override
    public void compile() { ensureCompiled(); }
}
