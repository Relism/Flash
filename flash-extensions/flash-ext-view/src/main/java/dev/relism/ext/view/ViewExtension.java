package dev.relism.ext.view;

import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;
import dev.relism.extension.FlashRegistrar;
import dev.relism.http.ContentType;
import dev.relism.routing.Middleware;

import java.util.List;
import java.util.Objects;

/**
 * Installs the view layer into a Flash application.
 *
 * <h3>Managed mode (recommended)</h3>
 * Pass a {@link ViewEngineType} — the extension auto-configures the engine,
 * detects dev mode for cache settings, and validates classpath dependencies at boot:
 * <pre>{@code
 * app.install(new ViewExtension(ViewEngineType.THYMELEAF));
 * }</pre>
 *
 * <h3>Manual mode (BYOE)</h3>
 * Supply your own {@link ViewEngine} implementation for full control:
 * <pre>{@code
 * ViewEngine myEngine = (template, model, fragment) -> { ... };
 * app.install(new ViewExtension(myEngine));
 * }</pre>
 *
 * <h3>What gets installed</h3>
 * <ol>
 *   <li>{@link ViewEngine} and {@link Renderer} are bound in the {@link FlashContext} —
 *       any handler can retrieve them via {@code require(Renderer.class)}.</li>
 *   <li>An {@link dev.relism.extension.AnnotationProcessor} is registered: class-based
 *       handlers carrying {@link View @View} receive an injected rendering middleware
 *       that intercepts the handler return value, resolves the template + model, and
 *       delegates to the engine. No boilerplate required in the handler itself.</li>
 * </ol>
 *
 * <h3>Handler patterns</h3>
 * <pre>{@code
 * // Declarative — annotation drives template selection
 * @Route(method = HttpMethod.GET, path = "/")
 * @View("home")
 * public class HomeHandler extends RequestHandler {
 *     public Object handle(Request req, Response res) {
 *         return Map.of("posts", service.findAll());  // model → home.html
 *     }
 * }
 *
 * // Dynamic override via Template signal
 * @View("list")
 * public class ListHandler extends RequestHandler {
 *     public Object handle(Request req, Response res) {
 *         if (error) return Template.of("error", Map.of("msg", "oops"));
 *         return data;                                // falls back to list.html
 *     }
 * }
 *
 * // Imperative — explicit render call (lambda-friendly)
 * Renderer renderer = app.ctx().require(Renderer.class);
 * app.get("/about", (req, res) -> renderer.view(res, "about"));
 * }</pre>
 *
 * <h3>Dev mode / cache</h3>
 * In managed mode, template caching is disabled when the JVM property
 * {@code flash.env=dev} or the environment variable {@code FLASH_ENV=dev} is set.
 *
 * <h3>Cross-extension integration</h3>
 * <pre>{@code
 * ctx.optional(ViewEngine.class).ifPresent(engine -> { ... });
 * }</pre>
 */
public final class ViewExtension implements FlashExtension {

    private final ViewEngine engine;

    // ── Constructors ──────────────────────────────────────────────────────────

    /**
     * Managed mode — auto-configures the engine selected by {@code type}.
     *
     * <p>Template caching is enabled unless {@code flash.env=dev} (JVM property)
     * or {@code FLASH_ENV=dev} (environment variable) is set.
     *
     * @param type the engine to use; must have its library on the runtime classpath
     * @throws IllegalStateException at boot time if the library is missing
     */
    public ViewExtension(ViewEngineType type) {
        this(type.createEngine(!isDevMode()));
    }

    /**
     * Manual mode — use a pre-constructed {@link ViewEngine} implementation.
     * Suitable for custom engines or engines that need non-default configuration.
     *
     * @param engine the engine implementation; must be thread-safe
     */
    public ViewExtension(ViewEngine engine) {
        this.engine = Objects.requireNonNull(engine, "ViewEngine must not be null");
    }

    // ── FlashExtension ────────────────────────────────────────────────────────

    @Override
    public void install(FlashRegistrar app, FlashContext ctx) {
        Renderer renderer = new Renderer(engine);
        ctx.provide(ViewEngine.class, engine);
        ctx.provide(Renderer.class,   renderer);

        ctx.addAnnotationProcessor(handlerClass -> {
            View view = findView(handlerClass);
            if (view == null) return List.of();

            String      defaultTemplate = view.value();
            ContentType contentType     = view.contentType();
            boolean     fragment        = view.fragment();

            // Injected once per handler at boot — zero overhead on the hot-path.
            // Intercepts the return value: Template signal overrides name+model;
            // any other value becomes the model for the annotation's template.
            Middleware renderingMiddleware = next -> (req, res) -> {
                Object result = next.handle(req, res);
                String tpl;
                Object model;
                if (result instanceof Template t) {
                    tpl   = t.name();
                    model = t.model();
                } else {
                    tpl   = defaultTemplate;
                    model = result;
                }
                res.setContentType(contentType);
                return engine.render(tpl, model, fragment);
            };

            return List.of(renderingMiddleware);
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Walks the superclass chain to find {@link View @View}.
     * Supports inheritance: a base handler can declare the view template and
     * concrete subclasses inherit it without re-annotating.
     */
    private static View findView(Class<?> cls) {
        while (cls != null && !cls.equals(Object.class)) {
            View v = cls.getAnnotation(View.class);
            if (v != null) return v;
            cls = cls.getSuperclass();
        }
        return null;
    }

    /**
     * Returns {@code true} when running in dev mode.
     * Checks JVM property {@code flash.env} first, then env var {@code FLASH_ENV}.
     */
    private static boolean isDevMode() {
        String prop = System.getProperty("flash.env");
        if (prop != null) return "dev".equalsIgnoreCase(prop);
        String env  = System.getenv("FLASH_ENV");
        return "dev".equalsIgnoreCase(env);
    }
}
