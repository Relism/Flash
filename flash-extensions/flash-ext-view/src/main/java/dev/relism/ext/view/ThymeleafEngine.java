package dev.relism.ext.view;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.context.IExpressionContext;
import org.thymeleaf.linkbuilder.ILinkBuilder;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Thymeleaf 3.x adapter for Flash SSR views.
 *
 * <p>Package-private — instantiated exclusively by {@link ViewEngineType#THYMELEAF}.
 *
 * <h3>Default configuration</h3>
 * <ul>
 *   <li>Prefix  : {@code /templates/}  (classpath-relative)</li>
 *   <li>Suffix  : {@code .html}</li>
 *   <li>Mode    : {@link TemplateMode#HTML}</li>
 *   <li>Encoding: UTF-8</li>
 *   <li>Cache   : enabled in production, disabled in dev mode
 *                 ({@code flash.env=dev} or {@code FLASH_ENV=dev})</li>
 * </ul>
 *
 * <h3>Link building</h3>
 * Thymeleaf's built-in {@code StandardLinkBuilder} requires an
 * {@code IWebContext} (servlet context) to resolve context-relative paths
 * ({@code @{/foo}}). Flash runs standalone, so this engine registers a custom
 * {@link FlashLinkBuilder} that resolves {@code @{...}} expressions without a
 * servlet context — path variables and query parameters are supported as usual.
 *
 * <h3>Model conventions</h3>
 * <ul>
 *   <li>{@link Map} model → each entry is a named Thymeleaf variable.</li>
 *   <li>Any other non-null value → registered under the key {@code "it"}.</li>
 *   <li>{@code null} model → empty context.</li>
 * </ul>
 */
final class ThymeleafEngine implements ViewEngineAdapter {

    private static final String PREFIX   = "/templates/";
    private static final String SUFFIX   = ".html";
    private final TemplateEngine engine;

    ThymeleafEngine(boolean cacheEnabled) {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix(PREFIX);
        resolver.setSuffix(SUFFIX);
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(cacheEnabled);

        this.engine = new TemplateEngine();
        this.engine.setTemplateResolver(resolver);
        // Replace the default StandardLinkBuilder (which requires IWebContext)
        // with our standalone-compatible link builder.
        this.engine.addLinkBuilder(FlashLinkBuilder.INSTANCE);
    }

    @Override
    public EngineCapabilities capabilities() {
        return new EngineCapabilities(true);
    }

    @Override
    public RenderOutput render(ViewTarget target, Map<String, Object> model,
                               dev.relism.models.Request req,
                               dev.relism.models.Response res) {
        String template = target.template();
        if (target.kind() == ViewKind.PAGE) {
            Context ctx = new Context();
            populateContext(ctx, model);
            return RenderOutput.html(engine.process(template, ctx));
        } else {
            String slot = target.slot();
            String fragment = (slot == null || slot.isBlank()) ? "content" : slot;
            Context ctx = new Context();
            populateContext(ctx, model);
            return RenderOutput.html(engine.process(template, Set.of(fragment), ctx));
        }
    }

    private static void populateContext(Context ctx, Object model) {
        if (model instanceof Map<?, ?> map) {
            map.forEach((k, v) -> ctx.setVariable(String.valueOf(k), v));
        } else if (model != null) {
            ctx.setVariable("it", model);
        }
    }

    // ── Link builder ──────────────────────────────────────────────────────────

    /**
     * Standalone-compatible link builder for Thymeleaf's {@code @{...}} expressions.
     *
     * <p>Thymeleaf's built-in {@code StandardLinkBuilder} requires an
     * {@code IWebContext} (i.e. a servlet container) to resolve context-relative
     * paths starting with {@code /}. This builder replicates that behaviour without
     * the servlet dependency:
     * <ul>
     *   <li>Path variables — {@code @{/posts/{id}(id=${post.id})}} → {@code /posts/abc}</li>
     *   <li>Query params  — {@code @{/search(q=${term})}}        → {@code /search?q=hello}</li>
     *   <li>Mixed         — {@code @{/posts/{id}(id=x,p=2)}}     → {@code /posts/x?p=2}</li>
     * </ul>
     * Registered at order {@link Integer#MIN_VALUE} so it takes precedence over
     * {@code StandardLinkBuilder} ({@code Integer.MAX_VALUE}).
     */
    private static final class FlashLinkBuilder implements ILinkBuilder {

        static final FlashLinkBuilder INSTANCE = new FlashLinkBuilder();

        @Override public String  getName()  { return "flash"; }
        @Override public Integer getOrder() { return Integer.MIN_VALUE; }

        @Override
        public String buildLink(IExpressionContext ctx,
                                String base,
                                Map<String, Object> params) {
            if (base == null) return "";
            String url = expandPathVars(base, params);
            return appendQueryString(url, base, params);
        }

        /** Substitutes {@code {key}} placeholders in the path with their encoded values. */
        private static String expandPathVars(String base, Map<String, Object> params) {
            if (params == null || params.isEmpty() || !base.contains("{")) return base;
            String result = base;
            for (var e : params.entrySet()) {
                String placeholder = '{' + e.getKey() + '}';
                if (result.contains(placeholder) && e.getValue() != null) {
                    result = result.replace(placeholder, encode(String.valueOf(e.getValue())));
                }
            }
            return result;
        }

        /** Appends parameters that were NOT consumed as path variables as {@code ?k=v&…} pairs. */
        private static String appendQueryString(String url, String base, Map<String, Object> params) {
            if (params == null || params.isEmpty()) return url;
            StringBuilder qs = new StringBuilder();
            for (var e : params.entrySet()) {
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
