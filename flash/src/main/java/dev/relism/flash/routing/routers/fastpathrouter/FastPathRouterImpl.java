package dev.relism.flash.routing.routers.fastpathrouter;

import dev.relism.fpr.core.ByteView;
import dev.relism.fpr.core.FastPathRouter;
import dev.relism.fpr.core.MatchResult;
import dev.relism.fpr.core.RouterBuilder;
import dev.relism.fpr.core.dsl.StringRouteParser;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.routing.AbstractRouter;

/**
 * Router backed by the {@code fpr-core} byte-level state machine. Routes are compiled lazily
 * on the first request and recompiled when routes are added after startup. Matching runs on a
 * virtual {@code METHOD + path} byte sequence in a single pass; {@link MatchResult} and
 * {@link FastPathViews.MethodPathByteView} are reused per-thread to avoid hot-path allocations.
 */
public class FastPathRouterImpl extends AbstractRouter {
    private final RouterBuilder<RequestHandler> builder = new RouterBuilder<>();
    private volatile FastPathRouter<ByteView, RequestHandler> router;
    private          String[]                                 cachedParamNames;

    public FastPathRouterImpl() {}

    private static final class FastPathRouterContext {
        private static final ThreadLocal<MatchResult<RequestHandler>> RESULT_HOLDER =
                ThreadLocal.withInitial(() -> new MatchResult<>(32, 128));
        private static final ThreadLocal<FastPathViews.MethodPathByteView> COMBINED_VIEW_HOLDER =
                ThreadLocal.withInitial(FastPathViews.MethodPathByteView::new);

        public static MatchResult<RequestHandler> getResult() {
            return RESULT_HOLDER.get();
        }

        public static FastPathViews.MethodPathByteView getCombinedView() {
            return COMBINED_VIEW_HOLDER.get();
        }
    }

    @Override
    protected AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler) {
        builder.add(StringRouteParser.parse(method.name() + path), handler);
        this.router = null;
        return this;
    }

    @Override
    public RequestHandler route(Request request) {
        ensureCompiled();

        MatchResult<RequestHandler> result = FastPathRouterContext.getResult();
        result.reset();

        HttpMethod method = request.getRequestLine().getMethod();
        ByteView pathView = request.getRequestLine().getPath();
        FastPathViews.MethodPathByteView combinedView = FastPathRouterContext.getCombinedView();
        combinedView.reset(method.getBytes(), pathView);

        int labelId = router.match(combinedView, result);

        if (labelId == FastPathRouter.NO_MATCH) {
            return null;
        }

        int count = result.paramCount();
        if (count > 0) {
            int methodLen   = method.getBytes().length;
            String[] all    = cachedParamNames;
            String[] names  = new String[count];
            int[]    starts = new int[count];
            int[]    lens   = new int[count];

            for (int i = 0; i < count; i++) {
                names[i]  = all[result.keyIdAt(i)];
                starts[i] = result.startAt(i) - methodLen;
                lens[i]   = result.lenAt(i);
            }
            setPathParams(request, names, pathView, starts, lens);
        }

        return result.handler();
    }

    private void ensureCompiled() {
        if (router == null) {
            synchronized (this) {
                if (router == null) {
                    cachedParamNames = builder.paramNames(); // written before volatile router
                    router = builder.compile();              // volatile write: establishes happens-before
                }
            }
        }
    }

    @Override
    public void compile() { ensureCompiled(); }
}
