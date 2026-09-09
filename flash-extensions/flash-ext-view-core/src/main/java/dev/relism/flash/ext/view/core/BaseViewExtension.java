package dev.relism.flash.ext.view.core;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.routing.MiddlewareNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public abstract class BaseViewExtension<TTarget> implements FlashExtension {
    private final List<GlobalValue> globals = new ArrayList<>();

    protected BaseViewExtension() {}

    /**
     * Seeds this instance with globals carried over from a prior one. Subclasses whose
     * fluent settings methods return a new instance (e.g. {@code JteExtension.templateRoot(...)})
     * must route through this constructor — otherwise {@link #addGlobal} calls made before
     * such a method silently vanish, since the new instance would start with an empty list.
     */
    protected BaseViewExtension(List<GlobalValue> seedGlobals) {
        globals.addAll(seedGlobals);
    }

    /** Snapshot of globals registered so far — for subclasses to carry over into a new instance. */
    protected final List<GlobalValue> globals() {
        return List.copyOf(globals);
    }

    public BaseViewExtension<TTarget> addGlobal(String key, Function<Request, Object> resolver) {
        String k = Objects.requireNonNull(key, "global key must not be null").trim();
        if (k.isEmpty()) {
            throw new IllegalArgumentException("global key must not be blank");
        }
        if (k.contains(".")) {
            throw new IllegalArgumentException("global key must be a simple key (no dots), received: " + key);
        }
        boolean duplicate = globals.stream().anyMatch(g -> g.key().equals(k));
        if (duplicate) {
            throw new IllegalArgumentException("global key '" + k + "' is already registered");
        }
        globals.add(new GlobalValue(k, Objects.requireNonNull(resolver, "global resolver must not be null")));
        return this;
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ViewRuntimeBridge<TTarget> runtime = createRuntime(List.copyOf(globals));
        try {
            ctx.provide(ViewRuntimeBridge.class, runtime);
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Another flash-ext-view implementation is already installed in "
                    + "this FlashApp. Only one view engine (e.g. ThymeleafExtension or JteExtension, not both) "
                    + "can be active per app — install a single implementation.", e);
        }
        ctx.addAnnotationProcessor(handlerClass -> {
            validateHandlerClass(handlerClass);
            return List.of();
        });
    }

    protected abstract ViewRuntimeBridge<TTarget> createRuntime(List<GlobalValue> globals);
    protected abstract void validateHandlerClass(Class<? extends RequestHandler> handlerClass);
}
