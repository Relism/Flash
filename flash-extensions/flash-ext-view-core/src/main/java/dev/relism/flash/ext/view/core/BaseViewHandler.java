package dev.relism.flash.ext.view.core;

import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.Response;

public abstract class BaseViewHandler<TTarget> extends RequestHandler {
    private static final byte DISPATCH_RENDER_2 = 1;
    private static final byte DISPATCH_RENDER_1 = 2;

    private ViewRuntimeBridge<TTarget> runtime;
    private TTarget target;
    private byte dispatchMode;

    public ViewModel render(Request req) throws Exception {
        throw new UnsupportedOperationException("Override render(Request) or render(Request, Response)");
    }

    public ViewModel render(Request req, Response res) throws Exception {
        return render(req);
    }

    @Override
    protected final void onInit() {
        @SuppressWarnings("unchecked")
        ViewRuntimeBridge<TTarget> resolvedRuntime = (ViewRuntimeBridge<TTarget>) require(ViewRuntimeBridge.class);
        runtime = resolvedRuntime;
        target = runtime.resolve(getClass());
        dispatchMode = resolveDispatchMode(getClass());
        onViewInit();
    }

    protected void onViewInit() {}

    @Override
    public final Object handle(Request request, Response response) throws Exception {
        RenderedView rendered = runtime.render(this, target, request, response);
        if (rendered.contentType() != null) {
            response.type(rendered.contentType());
        }
        return rendered.body();
    }

    public final ViewModel renderInternal(Request req, Response res) throws Exception {
        if (dispatchMode == 0) {
            dispatchMode = resolveDispatchMode(getClass());
        }
        return switch (dispatchMode) {
            case DISPATCH_RENDER_2 -> render(req, res);
            case DISPATCH_RENDER_1 -> render(req);
            default -> throw new UnsupportedOperationException("No render method overridden for " + getClass().getName());
        };
    }

    private static byte resolveDispatchMode(Class<?> handlerClass) {
        if (overrides(handlerClass, "render", Request.class, Response.class)) return DISPATCH_RENDER_2;
        if (overrides(handlerClass, "render", Request.class)) return DISPATCH_RENDER_1;
        return 0;
    }

    private static boolean overrides(Class<?> handlerClass, String method, Class<?>... args) {
        try {
            return handlerClass.getMethod(method, args).getDeclaringClass() != BaseViewHandler.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }
}
