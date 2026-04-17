package dev.relism.ext.view;

import dev.relism.Flash;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Installs server-side view rendering for class-based handlers.
 *
 * <p>Strict contract: only {@link ViewHandler} subclasses may declare {@link Page}/{@link Partial}.
 * Annotating a plain {@link dev.relism.models.RequestHandler} fails fast at boot.
 */
public final class ViewExtension implements FlashExtension {

    private final ViewEngineAdapter adapter;
    private final List<GlobalBinding> globals = new ArrayList<>();

    public ViewExtension(ViewEngineType type) {
        this(type.createAdapter(!Flash.DEV));
    }

    public ViewExtension(ViewEngineAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "ViewEngineAdapter must not be null");
    }

    /**
     * Registers one request-scoped global value under {@code global.<key>}.
     *
     * <p>This is the only supported global registration API. Keep resolvers fast and side-effect free.
     */
    public ViewExtension addGlobal(String key, Function<dev.relism.models.Request, Object> resolver) {
        String k = Objects.requireNonNull(key, "global key must not be null").trim();
        if (k.isEmpty()) {
            throw new IllegalArgumentException("global key must not be blank");
        }
        if (k.equals("global") || k.contains(".")) {
            throw new IllegalArgumentException("global key must be a simple key (no dots), received: " + key);
        }
        globals.add(new GlobalBinding(k, Objects.requireNonNull(resolver, "global resolver must not be null")));
        return this;
    }

    @Override
    public void provide(FlashContext ctx) {
        ViewRuntime runtime = new ViewRuntime(adapter, List.copyOf(globals));
        ctx.provide(ViewEngineAdapter.class, adapter);
        ctx.provide(ViewRuntime.class, runtime);

        // Processor kept for boot-time contract enforcement. Rendering itself stays in ViewHandler.
        ctx.addAnnotationProcessor(handlerClass -> {
            ViewTargetResolver.ResolvedView resolved = ViewTargetResolver.resolve(handlerClass, adapter.capabilities());
            if (resolved == null) return List.of();

            if (!ViewHandler.class.isAssignableFrom(handlerClass)) {
                throw new IllegalStateException("Handler " + handlerClass.getName()
                        + " declares @Page/@Partial but does not extend ViewHandler");
            }
            return List.of();
        });
    }
}
