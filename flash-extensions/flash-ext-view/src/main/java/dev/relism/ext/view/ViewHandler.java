package dev.relism.ext.view;

import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.Response;

public abstract class ViewHandler extends RequestHandler {
    private ViewRuntime runtime;
    private ViewTargetResolver.ResolvedView resolved;

    public ViewModel render(Request req) throws Exception {
        throw new UnsupportedOperationException("Override render(Request) or render(Request, Response)");
    }

    public ViewModel render(Request req, Response res) throws Exception {
        return render(req);
    }

    @Override
    protected final void onInit() {
        runtime = require(ViewRuntime.class);
        resolved = runtime.resolve(getClass());
        onViewInit();
    }

    protected void onViewInit() {}

    @Override
    public final Object handle(Request request, Response response) throws Exception {
        return runtime.render(this, resolved, request, response);
    }
}
