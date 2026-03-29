package dev.relism.ext.view;

/**
 * Explicit render signal returned from a handler to override the template name
 * and/or model chosen by {@link View @View}.
 *
 * <p>{@code Template} is a lightweight value object — it carries the template
 * name and an optional model, but performs no rendering itself. The
 * {@link ViewExtension}-injected middleware detects it at the call site and
 * delegates to the {@link ViewEngine}.
 *
 * <p>Use {@code Template} when:
 * <ul>
 *   <li>The handler is annotated with {@code @View} but needs to redirect to a
 *       different template dynamically (e.g. on validation failure).</li>
 *   <li>A lambda handler or a handler <em>without</em> {@code @View} wants to
 *       trigger rendering without registering the annotation — pair with a
 *       {@link Renderer} captured at construction time.</li>
 * </ul>
 *
 * <pre>{@code
 * // Inside a @View-annotated handler — overrides the default template on error
 * public Object handle(Request req, Response res) {
 *     if (!valid) return Template.of("form-error", Map.of("errors", errors));
 *     return service.findAll();   // falls back to @View template
 * }
 *
 * // Lambda handler — pair with Renderer captured from ctx at boot time
 * Renderer renderer = ctx.require(Renderer.class);
 * app.get("/page", (req, res) -> renderer.view(res, "page", model));
 * }</pre>
 */
public final class Template {

    private final String name;
    private final Object model;

    private Template(String name, Object model) {
        this.name  = name;
        this.model = model;
    }

    /** Creates a {@code Template} signal with the given name and model. */
    public static Template of(String name, Object model) {
        return new Template(name, model);
    }

    /** Creates a {@code Template} signal with a {@code null} model. */
    public static Template of(String name) {
        return new Template(name, null);
    }

    /** The template name/path to render. */
    public String name() {
        return name;
    }

    /** The model to bind; may be {@code null}. */
    public Object model() {
        return model;
    }
}
