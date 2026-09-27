package dev.relism.flash.ext.view.core;

import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

/** A template engine as {@link ViewHandler} uses it; one per app, added by its {@link ViewModule}. */
public interface ViewEngine {

    /** What {@code handler} renders, read from its annotations at boot, or null when they name nothing. */
    Object target(Class<?> handler);

    /** Renders {@code target} with {@code model} and the module's globals: the response body, its type set on {@code res}. */
    Object render(Object target, ViewModel model, Request req, Response res) throws Exception;
}
