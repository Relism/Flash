package dev.relism.flash.ext.view.core;

import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

public abstract class BaseViewExtension<TTarget> implements FlashExtension {
    private final List<GlobalValue> globals = new ArrayList<>();

    public BaseViewExtension<TTarget> addGlobal(String key, Function<Request, Object> resolver) {
        String k = Objects.requireNonNull(key, "global key must not be null").trim();
        if (k.isEmpty()) {
            throw new IllegalArgumentException("global key must not be blank");
        }
        if (k.equals("global") || k.contains(".")) {
            throw new IllegalArgumentException("global key must be a simple key (no dots), received: " + key);
        }
        globals.add(new GlobalValue(k, Objects.requireNonNull(resolver, "global resolver must not be null")));
        return this;
    }

    @Override
    public final void provide(FlashContext ctx) {
        ViewRuntimeBridge<TTarget> runtime = createRuntime(List.copyOf(globals));
        ctx.provide(ViewRuntimeBridge.class, runtime);
        ctx.addAnnotationProcessor(handlerClass -> {
            validateHandlerClass(handlerClass);
            return List.of();
        });
    }

    protected abstract ViewRuntimeBridge<TTarget> createRuntime(List<GlobalValue> globals);
    protected abstract void validateHandlerClass(Class<? extends RequestHandler> handlerClass);
}
