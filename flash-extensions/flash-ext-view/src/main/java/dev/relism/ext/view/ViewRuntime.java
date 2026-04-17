package dev.relism.ext.view;

import dev.relism.models.Request;
import dev.relism.models.Response;

import java.util.concurrent.ConcurrentHashMap;
import java.util.List;
import java.util.Map;

final class ViewRuntime {
    private static final ViewModel EMPTY = ViewModel.empty();

    private final ViewEngineAdapter adapter;
    private final List<ViewGlobals> globals;
    private final ConcurrentHashMap<Class<?>, ViewTargetResolver.ResolvedView> resolvedCache = new ConcurrentHashMap<>();

    ViewRuntime(ViewEngineAdapter adapter, List<ViewGlobals> globals) {
        this.adapter = adapter;
        this.globals = globals;
    }

    ViewTargetResolver.ResolvedView resolve(Class<?> handlerClass) {
        ViewTargetResolver.ResolvedView cached = resolvedCache.get(handlerClass);
        if (cached != null) return cached;
        ViewTargetResolver.ResolvedView resolved = ViewTargetResolver.resolve(handlerClass, adapter.capabilities());
        if (resolved == null) {
            throw new IllegalStateException("ViewHandler " + handlerClass.getName()
                    + " must declare @Page, @Partial, or @View");
        }
        resolvedCache.put(handlerClass, resolved);
        return resolved;
    }

    Object render(ViewHandler handler, ViewTargetResolver.ResolvedView resolved, Request req, Response res) throws Exception {
        if (resolved == null) {
            throw new IllegalStateException("ViewHandler " + handler.getClass().getName()
                    + " must declare @Page, @Partial, or @View");
        }

        ViewModel local = handler.render(req, res);
        ViewModel merged = merge(req, local);

        RenderOutput out = adapter.render(resolved.target(), merged.toMap(), req, res);
        if (out.contentType() != null) {
            res.type(out.contentType());
        } else {
            res.type(resolved.contentType());
        }
        return out.body();
    }

    ViewModel merge(Request req, ViewModel local) {
        ViewModel global = computeGlobals(req);
        return global.merge(local == null ? EMPTY : local);
    }

    private ViewModel computeGlobals(Request req) {
        if (globals.isEmpty()) return EMPTY;
        ViewModel out = ViewModel.empty();
        for (ViewGlobals provider : globals) {
            ViewModel vm = provider.provide(req);
            if (vm != null) out.withAll(vm.toMap());
        }
        return out;
    }

    static ViewModel legacyLocalModel(Object legacyModel) {
        if (legacyModel == null) return ViewModel.empty();
        if (legacyModel instanceof ViewModel vm) return ViewModel.copyOf(vm);
        if (legacyModel instanceof Map<?, ?> map) {
            ViewModel vm = ViewModel.empty();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                vm.with(String.valueOf(e.getKey()), ViewModel.unwrapValue(e.getValue()));
            }
            return vm;
        }
        return ViewModel.of("it", legacyModel);
    }
}
