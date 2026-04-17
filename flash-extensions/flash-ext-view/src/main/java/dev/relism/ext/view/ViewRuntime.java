package dev.relism.ext.view;

import dev.relism.models.Request;
import dev.relism.models.Response;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Internal runtime for view target resolution and per-request rendering.
 *
 * <p>All expensive reflection is done once and cached per handler class.
 */
final class ViewRuntime {
    private static final String GLOBAL_NAMESPACE = "global";

    private final ViewEngineAdapter adapter;
    private final List<GlobalBinding> globals;
    private final ConcurrentHashMap<Class<?>, ViewTargetResolver.ResolvedView> resolvedCache = new ConcurrentHashMap<>();

    ViewRuntime(ViewEngineAdapter adapter, List<GlobalBinding> globals) {
        this.adapter = adapter;
        this.globals = globals;
    }

    ViewTargetResolver.ResolvedView resolve(Class<?> handlerClass) {
        ViewTargetResolver.ResolvedView cached = resolvedCache.get(handlerClass);
        if (cached != null) return cached;
        ViewTargetResolver.ResolvedView resolved = ViewTargetResolver.resolve(handlerClass, adapter.capabilities());
        if (resolved == null) {
            throw new IllegalStateException("ViewHandler " + handlerClass.getName()
                    + " must declare @Page or @Partial");
        }
        resolvedCache.put(handlerClass, resolved);
        return resolved;
    }

    Object render(ViewHandler handler, ViewTargetResolver.ResolvedView resolved, Request req, Response res) throws Exception {
        if (resolved == null) {
            throw new IllegalStateException("ViewHandler " + handler.getClass().getName()
                    + " must declare @Page or @Partial");
        }

        RenderOutput out = render(resolved, req, res, handler.render(req, res));
        return out.body();
    }

    RenderOutput render(ViewTargetResolver.ResolvedView resolved, Request req, Response res, ViewModel local) throws Exception {
        ViewModel merged = merge(req, local);
        RenderOutput out = adapter.render(resolved.target(), merged.toMap(), req, res);

        if (out.contentType() != null) {
            res.type(out.contentType());
        } else {
            res.type(resolved.contentType());
        }
        return out;
    }

    ViewModel merge(Request req, ViewModel local) {
        // Single-pass merge: reserved global namespace + local model.
        // We avoid intermediate ViewModel allocations on the hot path.
        LinkedHashMap<String, Object> values = null;

        if (!globals.isEmpty()) {
            LinkedHashMap<String, Object> globalMap = new LinkedHashMap<>();
            for (GlobalBinding binding : globals) {
                Object resolved = binding.resolver().apply(req);
                globalMap.put(binding.key(), ViewModel.unwrapValue(resolved));
            }
            if (!globalMap.isEmpty()) {
                if (values == null) values = new LinkedHashMap<>();
                values.put(GLOBAL_NAMESPACE, Collections.unmodifiableMap(globalMap));
            }
        }

        if (local != null) {
            var localMap = local.toMap();
            if (localMap.containsKey(GLOBAL_NAMESPACE)) {
                throw new IllegalStateException("ViewModel key 'global' is reserved for framework globals");
            }
            if (values == null) return ViewModel.copyOf(local);
            values.putAll(localMap);
        }

        if (values == null || values.isEmpty()) return ViewModel.empty();
        return ViewModel.owned(values);
    }
}
