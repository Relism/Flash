package dev.relism.ext.view;

/**
 * Contract for template engine integrations.
 *
 * <p>Implement this interface to plug any template engine (Thymeleaf, Jinjava,
 * Mustache, FreeMarker, …) into the Flash view layer. A single instance is
 * shared across all handlers, so implementations must be thread-safe.
 *
 * <pre>{@code
 * // Thymeleaf example
 * ViewEngine thymeleaf = (template, model, fragment) -> {
 *     Context ctx = new Context();
 *     if (model instanceof Map<?,?> m) m.forEach((k, v) -> ctx.setVariable(k.toString(), v));
 *     else if (model != null) ctx.setVariable("model", model);
 *     return engine.process(fragment ? template + " :: fragment" : template, ctx);
 * };
 *
 * app.install(new ViewExtension(thymeleaf));
 * }</pre>
 */
@FunctionalInterface
public interface ViewEngine {

    /**
     * Renders {@code template} with the supplied {@code model}.
     *
     * @param template the template name or path — engine-specific convention
     *                 (e.g. {@code "views/home"}, {@code "home.html"})
     * @param model    the model object passed to the template; may be {@code null}
     * @param fragment if {@code true}, only a named fragment inside the template
     *                 should be rendered (Thymeleaf: {@code template :: fragment},
     *                 Mustache: partial name, etc.)
     * @return the rendered output string
     * @throws Exception any rendering error — propagated as a 500 by the Flash runtime
     */
    String render(String template, Object model, boolean fragment) throws Exception;

    /**
     * Convenience overload — renders the full template ({@code fragment = false}).
     */
    default String render(String template, Object model) throws Exception {
        return render(template, model, false);
    }
}
