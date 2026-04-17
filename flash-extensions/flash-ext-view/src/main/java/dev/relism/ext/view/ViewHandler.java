package dev.relism.ext.view;

import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;

/**
 * Base class for class-based SSR handlers.
 *
 * <p>Subclass contract:
 * <ol>
 *   <li>Declare exactly one of {@link Page} or {@link Partial} on the class hierarchy.</li>
 *   <li>Cache dependencies in {@link #onViewInit()} (one-time, boot-time).</li>
 *   <li>Build per-request model in {@link #render(Request)} or {@link #render(Request, Response)}.</li>
 * </ol>
 */
public abstract class ViewHandler extends RequestHandler {
    private ViewRuntime runtime;
    private ViewTargetResolver.ResolvedView resolved;

    /**
     * Per-request model hook.
     *
     * <p>Override this method for request-only rendering. If you need to mutate response
     * metadata while building the model, override {@link #render(Request, Response)}.
     */
    public ViewModel render(Request req) throws Exception {
        throw new UnsupportedOperationException("Override render(Request) or render(Request, Response)");
    }

    /**
     * Per-request model hook with response access.
     *
     * <p>Default implementation delegates to {@link #render(Request)}.
     */
    public ViewModel render(Request req, Response res) throws Exception {
        return render(req);
    }

    @Override
    protected final void onInit() {
        runtime  = require(ViewRuntime.class);
        resolved = runtime.resolve(getClass());
        onViewInit();
    }

    /**
     * One-time initialization hook invoked after view metadata resolution.
     *
     * <p>Use this to cache services via {@link #require(Class)}. Do not perform request-bound
     * work here.
     */
    protected void onViewInit() {}

    @Override
    public final Object handle(Request request, Response response) throws Exception {
        return runtime.render(this, resolved, request, response);
    }
}
