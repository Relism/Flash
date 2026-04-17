package dev.relism.ext.view;

import dev.relism.models.Request;
import dev.relism.models.Response;

import java.util.Map;

/**
 * Rendering adapter contract used by the Flash SSR view runtime.
 *
 * <p>Implementations must be thread-safe after construction because one instance is shared by
 * all requests.
 */
public interface ViewEngineAdapter {

    /** Engine feature flags used for boot-time route/view validation. */
    EngineCapabilities capabilities();

    /**
     * Renders a page/partial target with the merged model for the current request.
     *
     * @param target resolved rendering target
     * @param model merged request model (globals first, local model last)
     * @param req current request
     * @param res current response
     * @return response body + optional explicit content type
     */
    RenderOutput render(ViewTarget target, Map<String, Object> model, Request req, Response res) throws Exception;
}
