package dev.relism.flash.ext.view.thymeleaf;

import dev.relism.flash.Bound;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.Services;
import dev.relism.flash.ext.view.core.ViewEngine;
import dev.relism.flash.ext.view.core.ViewModel;
import dev.relism.flash.ext.view.core.ViewModule;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.ILinkBuilder;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Thymeleaf templates for {@link dev.relism.flash.ext.view.core.ViewHandler}s named with
 * {@link Template} or {@link Fragment}, read from the classpath. Globals are under {@code global}:
 * {@code ${global.user}}. Templates are cached outside dev.
 */
public final class ThymeleafModule extends ViewModule {

    private static final String GLOBAL = "global";
    private static final byte[] HTML = ContentType.ofExtension("html").getBytes(StandardCharsets.US_ASCII);

    private String prefix = "/templates/";
    private String suffix = ".html";
    private TemplateMode mode = TemplateMode.HTML;
    private Boolean cache;
    private String defaultFragment = "content";

    /** Where templates are on the classpath, {@code /templates/} by default. */
    public ThymeleafModule prefix(String prefix) {
        this.prefix = prefix.endsWith("/") ? prefix : prefix + '/';
        return this;
    }

    /** What every template name ends with, {@code .html} by default. */
    public ThymeleafModule suffix(String suffix) {
        this.suffix = suffix.startsWith(".") ? suffix : "." + suffix;
        return this;
    }

    public ThymeleafModule mode(TemplateMode mode) {
        this.mode = mode;
        return this;
    }

    /** Whether parsed templates are kept; by default outside dev only. */
    public ThymeleafModule cache(boolean cache) {
        this.cache = cache;
        return this;
    }

    /** The fragment a {@link Fragment} without a value renders, {@code content} by default. */
    public ThymeleafModule defaultFragment(String defaultFragment) {
        this.defaultFragment = defaultFragment;
        return this;
    }

    @Override
    public ThymeleafModule global(String key, Function<Request, Object> resolver) {
        super.global(key, resolver);
        return this;
    }

    @Override
    protected ViewEngine engine(Map<String, Function<Request, Object>> globals) {
        return new Engine(this, globals);
    }

    private record Target(String template, Set<String> fragments) {}

    private static final class Engine implements ViewEngine, Bound {

        private final ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        private final TemplateEngine thymeleaf = new TemplateEngine();
        private final Boolean cache;
        private final String defaultFragment;
        private final Map<String, Function<Request, Object>> globals;

        Engine(ThymeleafModule module, Map<String, Function<Request, Object>> globals) {
            resolver.setPrefix(module.prefix);
            resolver.setSuffix(module.suffix);
            resolver.setTemplateMode(module.mode);
            resolver.setCharacterEncoding("UTF-8");
            thymeleaf.setTemplateResolver(resolver);
            thymeleaf.addLinkBuilder(new Links());
            this.cache = module.cache;
            this.defaultFragment = module.defaultFragment;
            this.globals = globals;
        }

        @Override
        public void bind(Services services) {
            resolver.setCacheable(cache != null ? cache : !services.get(ServerConfig.class).isDev());
        }

        @Override
        public Object target(Class<?> handler) {
            Template template = handler.getAnnotation(Template.class);
            Fragment fragment = handler.getAnnotation(Fragment.class);
            if (template != null && fragment != null)
                throw new IllegalStateException(handler.getName() + " names both a @Template and a @Fragment");
            if (template != null) return new Target(named(template.value(), handler), null);
            if (fragment == null) return null;
            return new Target(named(fragment.template(), handler), Set.of(fragment.value().isBlank() ? defaultFragment : fragment.value()));
        }

        @Override
        public Object render(Object target, ViewModel model, Request req, Response res) throws Exception {
            Target view = (Target) target;
            Map<String, Object> values = new HashMap<>(model == null ? Map.of() : model.toMap());
            if (values.containsKey(GLOBAL)) throw new IllegalStateException("ViewModel key 'global' is reserved for the module's globals");
            if (!globals.isEmpty()) {
                Map<String, Object> resolved = new HashMap<>();
                globals.forEach((key, resolver) -> resolved.put(key, ViewModel.unwrapValue(resolver.apply(req))));
                values.put(GLOBAL, resolved);
            }
            ByteArrayOutputStream body = new ByteArrayOutputStream(1024);
            try (Writer out = new OutputStreamWriter(body, StandardCharsets.UTF_8)) {
                thymeleaf.process(view.template, view.fragments, new Context(null, values), out);
            }
            res.type(HTML);
            return body.toByteArray();
        }

        private static String named(String name, Class<?> handler) {
            if (name.isBlank()) throw new IllegalStateException(handler.getName() + " names a blank template");
            return name;
        }
    }

    /** {@code @{/users/{id}(id=${id},page=${page})}}: path variables filled, the rest as the query, both encoded. */
    private static final class Links implements ILinkBuilder {

        @Override public String getName() { return "flash"; }
        @Override public Integer getOrder() { return Integer.MIN_VALUE; }

        @Override
        public String buildLink(IExpressionContext ctx, String base, Map<String, Object> params) {
            if (base == null) return "";
            if (params == null || params.isEmpty()) return base;
            String url = base;
            StringBuilder query = new StringBuilder();
            for (Map.Entry<String, Object> param : params.entrySet()) {
                String placeholder = '{' + param.getKey() + '}';
                if (param.getValue() == null || base.contains(placeholder)) {
                    if (param.getValue() != null) url = url.replace(placeholder, encode(String.valueOf(param.getValue())));
                    continue;
                }
                query.append(query.isEmpty() ? '?' : '&').append(encode(param.getKey())).append('=').append(encode(String.valueOf(param.getValue())));
            }
            return url + query;
        }

        private static String encode(String s) {
            return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
        }
    }
}
