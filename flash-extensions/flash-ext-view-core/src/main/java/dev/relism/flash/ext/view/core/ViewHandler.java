package dev.relism.flash.ext.view.core;

import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.http.Handler;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;

/**
 * A handler that answers with a template: the engine's annotation names it, {@link #render} fills it.
 *
 * <pre>{@code
 * @GET("/") @Template("home.jte")
 * public final class Home extends ViewHandler {
 *     @Override public ViewModel render(Request req, Response res) { return ViewModel.of("name", "Ada"); }
 * }
 * }</pre>
 */
public abstract class ViewHandler implements Handler, Bound {

    private ViewEngine engine;
    private Object target;

    public abstract ViewModel render(Request req, Response res) throws Exception;

    @Override
    public final void bind(Services services) {
        engine = services.get(ViewEngine.class);
        target = engine.target(getClass());
        if (target == null) throw new IllegalStateException(getClass().getName() + " names no template");
    }

    @Override
    public final Object handle(Request req, Response res) throws Exception {
        return engine.render(target, render(req, res), req, res);
    }
}
