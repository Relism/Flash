package dev.relism.ext.view;

import dev.relism.http.ContentType;
import dev.relism.models.Response;

/**
 * Imperative view renderer — the programmatic counterpart to {@link View @View}.
 *
 * <p>Retrieve once at boot time in {@link dev.relism.models.RequestHandler#onInit onInit()},
 * cache in a private field, and call on the hot-path with zero lookup overhead:
 *
 * <pre>{@code
 * @Route(method = HttpMethod.GET, path = "/dashboard")
 * public class DashboardHandler extends RequestHandler {
 *     private Renderer renderer;
 *     private DashboardService svc;
 *
 *     @Override protected void onInit() {
 *         renderer = require(Renderer.class);
 *         svc      = require(DashboardService.class);
 *     }
 *
 *     @Override public Object handle(Request req, Response res) throws Exception {
 *         return renderer.view(res, "dashboard", Map.of("data", svc.stats()));
 *     }
 * }
 * }</pre>
 *
 * <p>For lambda handlers, capture {@code Renderer} from the context at registration
 * time — it is available immediately after {@code ViewExtension} is installed:
 *
 * <pre>{@code
 * app.install(new ViewExtension(engine));
 * Renderer renderer = app.ctx().require(Renderer.class);
 * app.get("/about", (req, res) -> renderer.view(res, "about"));
 * }</pre>
 *
 * <p>The underlying {@link ViewEngine} is thread-safe after construction — no
 * synchronization is needed on the hot-path.
 */
public final class Renderer {

    private final ViewEngine engine;

    /** Package-private — constructed exclusively by {@link ViewExtension}. */
    Renderer(ViewEngine engine) {
        this.engine = engine;
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    /**
     * Renders {@code template} with {@code model}, sets the {@code Content-Type}
     * header to {@code type}, and returns the rendered string as the handler body.
     */
    public String view(Response res, String template, Object model, ContentType type) throws Exception {
        res.setContentType(type);
        return engine.render(template, model);
    }

    /**
     * Renders {@code template} with {@code model} and sets
     * {@code Content-Type: text/html}.
     */
    public String view(Response res, String template, Object model) throws Exception {
        return view(res, template, model, ContentType.TEXT_HTML);
    }

    /** Renders {@code template} with a {@code null} model. */
    public String view(Response res, String template) throws Exception {
        return view(res, template, null);
    }

    // ── Template signal ───────────────────────────────────────────────────────

    /**
     * Creates a deferred {@link Template} signal that will be intercepted by the
     * {@link View @View} middleware. Use this from handlers that carry {@code @View}
     * but need to dynamically override the template name or supply a different model.
     *
     * <p>Does <em>not</em> render immediately — rendering happens in the middleware.
     */
    public Template template(String name, Object model) {
        return Template.of(name, model);
    }

    /** Creates a {@link Template} signal with a {@code null} model. */
    public Template template(String name) {
        return Template.of(name);
    }

    // ── Escape hatch ─────────────────────────────────────────────────────────

    /** Direct access to the underlying {@link ViewEngine} for advanced use cases. */
    public ViewEngine engine() {
        return engine;
    }
}
