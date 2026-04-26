package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.ext.view.core.BaseViewHandler;
import dev.relism.flash.ext.view.core.GlobalValue;
import dev.relism.flash.ext.view.core.RenderedView;
import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.ext.view.core.ViewRuntimeBridge;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.ILinkBuilder;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ThymeleafRuntime implements ViewRuntimeBridge<ThymeleafTarget> {
    private static final String GLOBAL_NAMESPACE = "global";

    private final ThymeleafSettings settings;
    private final List<GlobalValue> globals;
    private final ConcurrentHashMap<Class<?>, ThymeleafTarget> targets = new ConcurrentHashMap<>();
    private final TemplateEngine engine;

    ThymeleafRuntime(ThymeleafSettings settings, List<GlobalValue> globals) {
        this.settings = settings;
        this.globals = globals;
        this.engine = createEngine(settings);
    }

    @Override
    public ThymeleafTarget resolve(Class<?> handlerClass) {
        ThymeleafTarget cached = targets.get(handlerClass);
        if (cached != null) return cached;
        ThymeleafTarget resolved = ThymeleafTargetResolver.resolve(handlerClass, settings);
        if (resolved == null) {
            throw new IllegalStateException("ThymeleafHandler " + handlerClass.getName()
                    + " must declare @Template or @Fragment");
        }
        targets.put(handlerClass, resolved);
        return resolved;
    }

    @Override
    public RenderedView render(BaseViewHandler<ThymeleafTarget> handler,
                               ThymeleafTarget target,
                               Request req,
                               Response res) throws Exception {
        ViewModel local = handler.renderInternal(req, res);
        ViewModel merged = merge(req, local);

        Context ctx = new Context();
        for (Map.Entry<String, Object> e : merged.toMap().entrySet()) {
            ctx.setVariable(e.getKey(), e.getValue());
        }

        String output;
        if (target.kind() == ThymeleafTarget.Kind.TEMPLATE) {
            output = engine.process(target.template(), ctx);
        } else {
            output = engine.process(target.template(), Set.of(target.fragment()), ctx);
        }

        return new RenderedView(output, target.contentType());
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
                if (values == null) values = new LinkedHashMap<>();
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

    private static TemplateEngine createEngine(ThymeleafSettings settings) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(settings.prefix());
        resolver.setSuffix(settings.suffix());
        resolver.setTemplateMode(settings.mode());
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(settings.cacheEnabled());

        TemplateEngine engine = new TemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.addLinkBuilder(FlashLinkBuilder.INSTANCE);
        return engine;
    }

    private static final class FlashLinkBuilder implements ILinkBuilder {
        static final FlashLinkBuilder INSTANCE = new FlashLinkBuilder();

        @Override public String getName() { return "flash"; }
        @Override public Integer getOrder() { return Integer.MIN_VALUE; }

        @Override
        public String buildLink(IExpressionContext ctx, String base, Map<String, Object> params) {
            if (base == null) return "";
            String url = expandPathVars(base, params);
            return appendQueryString(url, base, params);
        }

        private static String expandPathVars(String base, Map<String, Object> params) {
            if (params == null || params.isEmpty() || !base.contains("{")) return base;
            String result = base;
            for (Map.Entry<String, Object> e : params.entrySet()) {
                String placeholder = '{' + e.getKey() + '}';
                if (result.contains(placeholder) && e.getValue() != null) {
                    result = result.replace(placeholder, encode(String.valueOf(e.getValue())));
                }
            }
            return result;
        }

        private static String appendQueryString(String url, String base, Map<String, Object> params) {
            if (params == null || params.isEmpty()) return url;
            StringBuilder qs = new StringBuilder();
            for (Map.Entry<String, Object> e : params.entrySet()) {
                if (base.contains('{' + e.getKey() + '}') || e.getValue() == null) continue;
                qs.append(qs.isEmpty() ? '?' : '&')
                        .append(encode(e.getKey()))
                        .append('=')
                        .append(encode(String.valueOf(e.getValue())));
            }
            return qs.isEmpty() ? url : url + qs;
        }

        private static String encode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }
}
