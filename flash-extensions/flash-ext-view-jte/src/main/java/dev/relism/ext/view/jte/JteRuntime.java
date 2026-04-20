package dev.relism.ext.view.jte;

import dev.relism.ext.view.core.BaseViewHandler;
import dev.relism.ext.view.core.GlobalValue;
import dev.relism.ext.view.core.RenderedView;
import dev.relism.ext.view.core.ViewModel;
import dev.relism.ext.view.core.ViewRuntimeBridge;
import dev.relism.models.Request;
import dev.relism.models.Response;
import gg.jte.CodeResolver;
import gg.jte.TemplateEngine;
import gg.jte.output.StringOutput;
import gg.jte.resolve.DirectoryCodeResolver;
import gg.jte.resolve.ResourceCodeResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class JteRuntime implements ViewRuntimeBridge<JteTarget> {
    private static final String GLOBAL_NAMESPACE = "global";

    private final JteSettings settings;
    private final List<GlobalValue> globals;
    private final ConcurrentHashMap<Class<?>, JteTarget> targets = new ConcurrentHashMap<>();
    private final TemplateEngine engine;

    JteRuntime(JteSettings settings, List<GlobalValue> globals) {
        this.settings = settings;
        this.globals = globals;
        this.engine = createEngine(settings);
    }

    @Override
    public JteTarget resolve(Class<?> handlerClass) {
        JteTarget cached = targets.get(handlerClass);
        if (cached != null) return cached;
        JteTarget resolved = JteTargetResolver.resolve(handlerClass, settings);
        if (resolved == null) {
            throw new IllegalStateException("JteHandler " + handlerClass.getName() + " must declare @Template");
        }
        targets.put(handlerClass, resolved);
        return resolved;
    }

    @Override
    public RenderedView render(BaseViewHandler<JteTarget> handler,
                               JteTarget target,
                               Request req,
                               Response res) throws Exception {
        ViewModel local = handler.renderInternal(req, res);
        Map<String, Object> merged = merge(req, local).toMap();
        StringOutput out = new StringOutput(1024);
        engine.render(target.template(), merged, out);
        return new RenderedView(out.toString(), target.contentType());
    }

    private ViewModel merge(Request req, ViewModel local) {
        LinkedHashMap<String, Object> values = null;

        if (!globals.isEmpty()) {
            LinkedHashMap<String, Object> globalMap = new LinkedHashMap<>();
            for (GlobalValue binding : globals) {
                Object resolved = binding.resolver().apply(req);
                globalMap.put(binding.key(), ViewModel.unwrapValue(resolved));
            }
            if (!globalMap.isEmpty()) {
                values = new LinkedHashMap<>();
                values.put(GLOBAL_NAMESPACE, Collections.unmodifiableMap(globalMap));
            }
        }

        if (local != null) {
            Map<String, Object> localMap = local.toMap();
            if (localMap.containsKey(GLOBAL_NAMESPACE)) {
                throw new IllegalStateException("ViewModel key 'global' is reserved for framework globals");
            }
            if (values == null) return ViewModel.copyOf(local);
            values.putAll(localMap);
        }

        if (values == null || values.isEmpty()) return ViewModel.empty();
        return ViewModel.owned(values);
    }

    private static TemplateEngine createEngine(JteSettings settings) {
        TemplateEngine engine;
        if (settings.usePrecompiled()) {
            Path path = settings.precompiledClassesPath();
            engine = Files.isDirectory(path)
                    ? TemplateEngine.createPrecompiled(path, settings.contentType())
                    : TemplateEngine.createPrecompiled(settings.contentType());
        } else {
            CodeResolver resolver = createResolver(settings.templateRoot(), Thread.currentThread().getContextClassLoader());
            engine = TemplateEngine.create(resolver, settings.dynamicClassesPath(), settings.contentType());
        }
        engine.setBinaryStaticContent(settings.binaryStaticContent());
        return engine;
    }

    private static CodeResolver createResolver(String templateRoot, ClassLoader classLoader) {
        String root = templateRoot.startsWith("/") ? templateRoot.substring(1) : templateRoot;
        Path maybeDir = Path.of(root);
        if (Files.isDirectory(maybeDir)) {
            return new DirectoryCodeResolver(maybeDir);
        }
        return new ResourceCodeResolver(root, classLoader);
    }
}
