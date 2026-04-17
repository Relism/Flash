package dev.relism.ext.view;

import dev.relism.models.Request;
import dev.relism.models.Response;

import java.util.Map;

public interface ViewEngineAdapter {
    EngineCapabilities capabilities();
    RenderOutput render(ViewTarget target, Map<String, Object> model, Request req, Response res) throws Exception;
}
