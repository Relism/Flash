package dev.relism.flash.routing.routers.fastpathrouter;

import dev.relism.fpr.core.ByteView;
import dev.relism.fpr.core.FastPathRouter;
import dev.relism.fpr.core.MatchResult;
import dev.relism.fpr.core.RouterBuilder;
import dev.relism.fpr.core.dsl.StringRouteParser;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.PathParams;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.routing.AbstractRouter;

import java.util.Arrays;

/**
 * Router backed by the {@code fpr-core} byte-level state machine. Routes are compiled lazily
 * on the first request and recompiled when routes are added after startup. Matching runs on a
 * virtual {@code METHOD + path} byte sequence in a single pass; the per-connection
 * {@link RouteScratch} ({@link #newScratch}) owns the reused {@link MatchResult},
 * {@link FastPathViews.MethodPathByteView} and path-param arrays that would otherwise allocate
 * request.
 */
public class FastPathRouterImpl extends AbstractRouter {
    private final RouterBuilder<RequestHandler> builder = new RouterBuilder<>();
    private volatile FastPathRouter<ByteView, RequestHandler> router;
    private          String[]                                 cachedParamNames;

    public FastPathRouterImpl() {}

    /**
     * together. Created once per connection by {@link #newScratch} and threaded back into every
     * {@link #route} call for that connection's lifetime (see {@link AbstractRouter#newScratch}
     * for why this replaced the two {@code ThreadLocal}s this class used to hold).
     *
     * grow (doubling, via {@link #ensureParamCapacity}) to the connection's high-water mark —
     * the number of path params the most param-heavy route matched on this connection ever
     * needed — and are never shrunk back down or reallocated once warm, the same amortized policy
     * {@code RequestParser}'s read buffer already uses. {@code pathParams} is the single
     * {@link PathParams} instance repositioned (via {@link PathParams#reset}) over those arrays
     * every time a match has params, instead of a fresh {@code PathParams} per request.
     */
    static final class RouteScratch {
        final MatchResult<RequestHandler> matchResult = new MatchResult<>(32, 128);
        final FastPathViews.MethodPathByteView combinedView = new FastPathViews.MethodPathByteView();

        String[] paramNames  = new String[8];
        int[]    paramStarts = new int[8];
        int[]    paramLens   = new int[8];
        PathParams pathParams = new PathParams(paramNames, paramStarts, paramLens);

        void ensureParamCapacity(int count) {
            if (count <= paramNames.length) return;
            int grown = paramNames.length;
            while (grown < count) grown *= 2;
            paramNames  = Arrays.copyOf(paramNames, grown);
            paramStarts = Arrays.copyOf(paramStarts, grown);
            paramLens   = Arrays.copyOf(paramLens, grown);
            // The arrays PathParams reads are now different instances — rebuild it. This is the
            // only case in which a RouteScratch allocates past connection setup, and only on a
            // connection whose route mix keeps needing more params than ever seen before; it
            // never happens again once this connection's high-water mark stabilizes.
            pathParams = new PathParams(paramNames, paramStarts, paramLens);
        }
    }

    @Override
    public Object newScratch() {
        return new RouteScratch();
    }

    @Override
    protected AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler) {
        builder.add(StringRouteParser.parse(method.name() + path), handler);
        this.router = null;
        return this;
    }

    @Override
    public RequestHandler route(Request request, Object scratchObj) {
        ensureCompiled();
        RouteScratch scratch = (RouteScratch) scratchObj;

        MatchResult<RequestHandler> result = scratch.matchResult;
        result.reset();

        HttpMethod method = request.getRequestLine().getMethod();
        ByteView pathView = request.getRequestLine().getPath();
        FastPathViews.MethodPathByteView combinedView = scratch.combinedView;
        combinedView.reset(method.getBytes(), pathView);

        int labelId = router.match(combinedView, result);

        if (labelId == FastPathRouter.NO_MATCH) {
            return null;
        }

        int count = result.paramCount();
        if (count > 0) {
            scratch.ensureParamCapacity(count);
            int methodLen  = method.getBytes().length;
            String[] all   = cachedParamNames;
            String[] names = scratch.paramNames;
            int[] starts   = scratch.paramStarts;
            int[] lens     = scratch.paramLens;

            for (int i = 0; i < count; i++) {
                names[i]  = all[result.keyIdAt(i)];
                starts[i] = result.startAt(i) - methodLen;
                lens[i]   = result.lenAt(i);
            }
            scratch.pathParams.reset(pathView, count);
            PathParams.inject(request, scratch.pathParams);
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
