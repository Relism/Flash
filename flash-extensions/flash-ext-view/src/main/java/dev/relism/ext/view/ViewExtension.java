package dev.relism.ext.view;

import dev.relism.Flash;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;
import dev.relism.http.ContentType;
import dev.relism.routing.Middleware;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class ViewExtension implements FlashExtension {

    private final ViewEngineAdapter adapter;
    private final ViewEngine legacyEngine;
    private final List<ViewGlobals> globals = new ArrayList<>();

    public ViewExtension(ViewEngineType type) {
        this(type.createAdapter(!Flash.DEV));
    }

    public ViewExtension(ViewEngine engine) {
        this(new LegacyViewEngineAdapter(engine), engine);
    }

    public ViewExtension(ViewEngineAdapter adapter) {
        this(adapter, null);
    }

    private ViewExtension(ViewEngineAdapter adapter, ViewEngine legacyEngine) {
        this.adapter = Objects.requireNonNull(adapter, "ViewEngineAdapter must not be null");
        this.legacyEngine = legacyEngine;
    }

    public ViewExtension addGlobals(ViewGlobals provider) {
        globals.add(Objects.requireNonNull(provider, "ViewGlobals must not be null"));
        return this;
    }

    @Override
    public void provide(FlashContext ctx) {
        ViewRuntime runtime = new ViewRuntime(adapter, List.copyOf(globals));
        ctx.provide(ViewEngineAdapter.class, adapter);
        ctx.provide(ViewRuntime.class, runtime);

        if (legacyEngine != null) {
            ctx.provide(ViewEngine.class, legacyEngine);
            ctx.provide(Renderer.class, new Renderer(legacyEngine));
        } else if (adapter instanceof ViewEngine engine) {
            ctx.provide(ViewEngine.class, engine);
            ctx.provide(Renderer.class, new Renderer(engine));
        }

        ctx.addAnnotationProcessor(handlerClass -> {
            if (ViewHandler.class.isAssignableFrom(handlerClass)) return List.of();

            ViewTargetResolver.ResolvedView resolved = ViewTargetResolver.resolve(handlerClass, adapter.capabilities());
            if (resolved == null) return List.of();

            Middleware renderingMiddleware = next -> (req, res) -> {
                Object result = next.handle(req, res);
                ViewModel local = ViewRuntime.legacyLocalModel(result);
                ViewModel merged = runtime.merge(req, local);
                RenderOutput out = adapter.render(resolved.target(), merged.toMap(), req, res);

                if (resolved.legacy()) {
                    ContentType type = resolved.contentType();
                    if (type != null) res.type(type);
                    else if (out.contentType() != null) res.type(out.contentType());
                } else if (out.contentType() != null) {
                    res.type(out.contentType());
                }
                return out.body();
            };

            return List.of(renderingMiddleware);
        });
    }
}
