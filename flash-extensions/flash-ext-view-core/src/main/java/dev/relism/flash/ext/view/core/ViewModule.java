package dev.relism.flash.ext.view.core;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.http.Request;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.RouteHook;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What every template engine module shares: values every template sees ({@link #global}), and a
 * boot-time check that a handler naming a template is a {@link ViewHandler}.
 */
public abstract class ViewModule implements Module {

    private final Map<String, Function<Request, Object>> globals = new LinkedHashMap<>();

    /** A value every template sees, computed per request. */
    public ViewModule global(String key, Function<Request, Object> resolver) {
        if (key.isBlank() || key.contains(".")) throw new IllegalArgumentException("A global is a simple key, not '" + key + "'");
        if (globals.putIfAbsent(key, Objects.requireNonNull(resolver)) != null)
            throw new IllegalArgumentException("Global '" + key + "' is already registered");
        return this;
    }

    @Override
    public void install(App app) {
        ViewEngine engine = engine(Map.copyOf(globals));
        app.add(ViewEngine.class, engine).add(RouteHook.class, new RouteHook() {
            @Override public void routed(Route route) {
                Class<?> handler = route.handler();
                if (handler != null && !ViewHandler.class.isAssignableFrom(handler) && engine.target(handler) != null)
                    throw new IllegalStateException(handler.getName() + " names a template but is no ViewHandler");
            }
        });
    }

    /** This module's engine, rendering with {@code globals}. */
    protected abstract ViewEngine engine(Map<String, Function<Request, Object>> globals);
}
