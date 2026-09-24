package dev.relism.flash.ext.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.RouteEvent;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.routing.Route;

import java.nio.charset.StandardCharsets;

/**
 * Generates and serves an OpenAPI 3.0 spec, and the documentation page that reads it, under a
 * configurable base path.
 *
 * <p>Given {@code basePath = "/openapi"} (the default), three routes are registered:
 * <ul>
 *   <li>{@code GET /openapi.json} — OpenAPI 3.0 spec as JSON</li>
 *   <li>{@code GET /openapi.yaml} — the same document as YAML</li>
 *   <li>{@code GET /openapi/docs} — the {@link Ui}, Swagger UI unless another is chosen</li>
 * </ul>
 *
 * <p>If {@code flash-ext-jackson} is installed, this extension reuses its {@link ObjectMapper}.
 * Otherwise it uses a local default mapper.
 *
 * <p>Every class-based route is collected at boot, whether or not it is annotated: a path, the
 * body its handler takes and what it returns are already in the code. {@link ApiOperation} adds
 * the prose.
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new JacksonExtension())
 *     .install(new OpenApiExtension("/openapi", "My API", "2.0.0").ui(Ui.scalar()))
 *     .start();
 * }</pre>
 */
public class OpenApiExtension implements FlashExtension {

    private static final byte[] YAML_CONTENT_TYPE = "application/yaml".getBytes(StandardCharsets.UTF_8);

    private final String basePath;
    private final String title;
    private final String version;
    private final String description;
    private Ui<?> ui = Ui.swagger();

    public OpenApiExtension() {
        this("/openapi", "API", "1.0.0", "");
    }

    public OpenApiExtension(String basePath) {
        this(basePath, "API", "1.0.0", "");
    }

    public OpenApiExtension(String basePath, String title, String version) {
        this(basePath, title, version, "");
    }

    public OpenApiExtension(String basePath, String title, String version, String description) {
        this.basePath    = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.title       = title;
        this.version     = version;
        this.description = description;
    }

    /** Which documentation page to serve, {@link Ui#swagger()} by default; {@link Ui#none()} serves none. */
    public OpenApiExtension ui(Ui<?> ui) {
        this.ui = ui;
        return this;
    }

    // ── FlashExtension ────────────────────────────────────────────────────────

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        OpenApiBuilder             builder  = new OpenApiBuilder().title(title).version(version).description(description);
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();

        ctx.provide(OpenApiContributorRegistry.class, registry);
        ctx.provide(OpenApiBuilder.class, builder);
        builder.setContributorRegistry(registry);

        // Collect operation metadata from final compiled routes.
        // This guarantees full runtime paths (namespaces/prefixes/rewrites) in the spec.
        ctx.addRouteListener(event -> addOperationFromEvent(builder, event));
        ctx.onReady(() -> {
            ObjectMapper json = ctx.find(ObjectMapper.class).orElseGet(() -> JsonMapper.builder().build());
            OpenApiBuilder spec = ctx.require(OpenApiBuilder.class);
            String jsonPath = basePath + ".json";

            Document asJson = new Document(json, spec);
            Document asYaml = new Document(new YAMLMapper(), spec);
            app.get(jsonPath,           (req, res) -> { res.type(ContentType.JSON).body(asJson.bytes());  return null; });
            app.get(basePath + ".yaml", (req, res) -> { res.type(YAML_CONTENT_TYPE).body(asYaml.bytes()); return null; });

            if (ui == null) return;
            byte[] page = ui.page(jsonPath, title, json);
            app.get(basePath + "/docs", (req, res) -> { res.type(ContentType.TEXT_HTML).body(page); return null; });
        });
    }

    /**
     * The document encoded once per revision. Routes are collected after the ready callbacks, so
     * the first request is what renders it; from then on the same array is handed out, and a
     * request allocates nothing. Two first requests at once encode the same bytes twice, which is
     * cheaper than the lock that would prevent it; the bytes are written before the revision that
     * publishes them, so a reader that sees the revision sees the whole array.
     */
    private static final class Document {

        private final ObjectMapper mapper;
        private final OpenApiBuilder spec;
        private volatile byte[] encoded;
        private volatile int revision = -1;

        Document(ObjectMapper mapper, OpenApiBuilder spec) {
            this.mapper = mapper;
            this.spec = spec;
        }

        byte[] bytes() throws JsonProcessingException {
            if (revision != spec.revision()) {
                encoded = mapper.writeValueAsBytes(spec.build());
                revision = spec.revision();
            }
            return encoded;
        }
    }

    /** Every class-based route is an operation; {@link ApiOperation} only adds what the code cannot say. */
    private static void addOperationFromEvent(OpenApiBuilder builder, RouteEvent event) {
        Class<?> handlerClass = event.handlerClass();
        if (handlerClass == null) return;   // a lambda route has nothing to read

        builder.addOperation(routeOf(event), handlerClass.getAnnotation(ApiOperation.class), handlerClass);
    }

    private static Route routeOf(RouteEvent event) {
        return new Route() {
            @Override public HttpMethod method() { return event.method(); }
            @Override public String path() { return event.path(); }
            @Override public Class<? extends java.lang.annotation.Annotation> annotationType() { return Route.class; }
        };
    }
}
