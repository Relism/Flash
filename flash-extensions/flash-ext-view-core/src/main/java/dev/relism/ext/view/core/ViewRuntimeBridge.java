package dev.relism.ext.view.core;

import dev.relism.models.Request;
import dev.relism.models.Response;

public interface ViewRuntimeBridge<TTarget> {
    TTarget resolve(Class<?> handlerClass);
    RenderedView render(BaseViewHandler<TTarget> handler, TTarget target, Request req, Response res) throws Exception;
}
