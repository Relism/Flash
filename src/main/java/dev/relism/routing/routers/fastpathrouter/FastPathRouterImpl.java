package dev.relism.routing.routers.fastpathrouter;

import dev.relism.fpr.core.ByteView;
import dev.relism.fpr.core.FastPathRouter;
import dev.relism.fpr.core.MatchResult;
import dev.relism.fpr.core.RouterBuilder;
import dev.relism.fpr.core.dsl.StringRouteParser;
import dev.relism.http.HttpMethod;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.routing.AbstractRouter;
import dev.relism.routing.PathUtils;

/**
 * Router implementation backed by the {@code fpr-core} byte-level state machine.
 *
 * <p>Routes are compiled lazily: the internal {@code FastPathRouter} is built on the first
 * incoming request after one or more routes have been added. Adding a route after the server
 * has started is safe — the router is marked dirty and recompiled on the next request.
 *
 * <p>Matching is done on a virtual {@code METHOD + path} byte sequence to avoid a two-step
 * lookup. Captured path parameters are exposed via {@link dev.relism.models.PathParams} on
 * the request object, accessible through {@link dev.relism.models.Request#getPathParams()}.
 *
 * <p>Thread safety: {@code FastPathRouter.match()} is safe for concurrent calls as of
 * {@code fpr-core} 1.1.0 — traversal state ({@code RouteSearch}, {@code SegmentCursor}) is
 * held in method-local variables, making the compiled router instance fully immutable.
 */
public class FastPathRouterImpl extends AbstractRouter {
    private final RouterBuilder<RequestHandler> builder = new RouterBuilder<>();
    private volatile FastPathRouter<ByteView, RequestHandler> router;
    private          String[]                                 cachedParamNames;

    /**
     * Thread-local holders for per-request reusable objects.
     * Both {@link MatchResult} and {@link FastPathViews.MethodPathByteView} are reset before use,
     * so no allocation occurs on the routing hot path.
     */
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
        String fullPath = PathUtils.join(namespace, path);
        builder.add(StringRouteParser.parse(method.name() + fullPath), handler);
        this.router = null; // Mark dirty
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

        // CAPTURE PARAMETERS
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

    /**
     * Ensures the FPR state machine is compiled. Uses double-checked locking on a {@code volatile}
     * field so the fast path (already compiled) is a single null-check with no synchronization.
     */
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
}
