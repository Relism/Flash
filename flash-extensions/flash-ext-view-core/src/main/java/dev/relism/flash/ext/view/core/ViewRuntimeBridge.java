package dev.relism.flash.ext.view.core;

import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;

public interface ViewRuntimeBridge<TTarget> {
    TTarget resolve(Class<?> handlerClass);
    RenderedView render(BaseViewHandler<TTarget> handler, TTarget target, Request req, Response res) throws Exception;
}
