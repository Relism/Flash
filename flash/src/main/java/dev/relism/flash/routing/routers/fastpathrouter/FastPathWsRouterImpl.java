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

public final class FastPathWsRouterImpl extends AbstractWsRouter {

    private final RouterBuilder<WebSocketHandler>               builder = new RouterBuilder<>();
    private volatile FastPathRouter<ByteView, WebSocketHandler> router;
    private          String[]                                   cachedParamNames;

    @Override
    protected AbstractWsRouter addRoute(HttpMethod method, String path, WebSocketHandler handler) {
        builder.add(StringRouteParser.parse(method.name() + path), handler);
        this.router = null;
        return this;
    }

    @Override
    public WebSocketHandler route(Request request) {
        ensureCompiled();

        MatchResult<WebSocketHandler> result = Context.result();
        result.reset();

        HttpMethod method = request.getRequestLine().getMethod();
        ByteView pathView = request.getRequestLine().getPath();
        FastPathViews.MethodPathByteView combined = Context.combined();
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

    private static final class Context {
        private static final ThreadLocal<MatchResult<WebSocketHandler>> RESULT =
                ThreadLocal.withInitial(() -> new MatchResult<>(32, 128));
        private static final ThreadLocal<FastPathViews.MethodPathByteView> COMBINED =
                ThreadLocal.withInitial(FastPathViews.MethodPathByteView::new);

        static MatchResult<WebSocketHandler> result() { return RESULT.get(); }
        static FastPathViews.MethodPathByteView combined() { return COMBINED.get(); }
    }
}
