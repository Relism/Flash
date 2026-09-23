package dev.relism.flash.ext.openapi;

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

/**
 * Generates and serves an OpenAPI 3.0 spec and Swagger UI under a configurable base path.
 *
 * <p>Given {@code basePath = "/openapi"} (the default), three routes are registered:
 * <ul>
 *   <li>{@code GET /openapi.json}    — OpenAPI 3.0 spec as JSON</li>
 *   <li>{@code GET /openapi.yaml}    — OpenAPI 3.0 spec as YAML</li>
 *   <li>{@code GET /openapi/swagger} — Swagger UI pointing at {@code /openapi.json}</li>
 * </ul>
 *
 * <p>If {@code flash-ext-jackson} is installed, this extension reuses its
 * {@link ObjectMapper}. Otherwise it uses a local default mapper.
 *
 * <p>Every class-based route is collected at boot, whether or not it is annotated: a path, the
 * body its handler takes and what it returns are already in the code. {@link ApiOperation} adds
 * the prose.
 *
 * <pre>{@code
 * FlashApp.create(8080)
 *     .install(new JacksonExtension())
 *     .install(new OpenApiExtension("/openapi", "My API", "2.0.0"))
 *     .start();
 * }</pre>
 */
public class OpenApiExtension implements FlashExtension {

    private static final String YAML_CONTENT_TYPE = "application/yaml";

    private final String basePath;
    private final String title;
    private final String version;
    private final String description;

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
            ObjectMapper jsonMapper = ctx.find(ObjectMapper.class).orElseGet(() -> JsonMapper.builder().build());
            YAMLMapper   yamlMapper = new YAMLMapper();
            OpenApiBuilder resolvedBuilder = ctx.require(OpenApiBuilder.class);

        String jsonPath    = basePath + ".json";
        String yamlPath    = basePath + ".yaml";
        String swaggerPath = basePath + "/swagger";
        String swaggerHtml = buildSwaggerHtml(jsonPath);

        app.get(jsonPath,    (req, res) -> { res.type(ContentType.JSON);     return jsonMapper.writeValueAsString(resolvedBuilder.build()); });
        app.get(yamlPath,    (req, res) -> { res.type(YAML_CONTENT_TYPE);              return yamlMapper.writeValueAsString(resolvedBuilder.build()); });
            app.get(swaggerPath, (req, res) -> { res.type(ContentType.TEXT_HTML); return swaggerHtml; });
        });
    }

    // ── Swagger UI HTML ───────────────────────────────────────────────────────

    private static String buildSwaggerHtml(String specJsonPath) {
        return "<!DOCTYPE html>\n" +
               "<html lang=\"en\">\n" +
               "<head>\n" +
               "  <meta charset=\"UTF-8\">\n" +
               "  <meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n" +
               "  <title>Swagger UI</title>\n" +
               "  <link rel=\"stylesheet\" href=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui.css\">\n" +
               "</head>\n" +
               "<body>\n" +
               "<div id=\"swagger-ui\"></div>\n" +
               "<script src=\"https://unpkg.com/swagger-ui-dist@5/swagger-ui-bundle.js\"></script>\n" +
               "<script>\n" +
               "SwaggerUIBundle({\n" +
               "  url: \"" + specJsonPath + "\",\n" +
               "  dom_id: '#swagger-ui',\n" +
               "  deepLinking: true,\n" +
               "  presets: [SwaggerUIBundle.presets.apis, SwaggerUIBundle.SwaggerUIStandalonePreset],\n" +
               "  layout: \"BaseLayout\"\n" +
               "});\n" +
               "</script>\n" +
               "</body>\n" +
                "</html>";
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
