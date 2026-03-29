package dev.relism.ext.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;
import dev.relism.extension.FlashRegistrar;
import dev.relism.http.ContentType;
import dev.relism.routing.Route;

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
 * <p><b>Requires</b> {@code flash-ext-jackson} to be installed first (shares its
 * {@link ObjectMapper}). The YAML endpoint uses its own {@link YAMLMapper} instance.
 *
 * <p>Operations are collected automatically from handlers annotated with
 * {@link ApiOperation} as they are registered via {@link FlashApp#register}.
 *
 * <pre>{@code
 * FlashApp.of(new HttpServer(config))
 *     .install(new JacksonExtension())
 *     .install(new OpenApiExtension("/openapi", "My API", "2.0.0"))
 *     .register(new BlogHandlers.Index())
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

    @Override
    public void install(FlashRegistrar app, FlashContext ctx) {
        ObjectMapper jsonMapper = ctx.require(ObjectMapper.class);
        YAMLMapper   yamlMapper = new YAMLMapper();

        OpenApiBuilder builder = new OpenApiBuilder()
                .title(title)
                .version(version)
                .description(description);

        OpenApiSecurityRegistry secRegistry = new OpenApiSecurityRegistry();
        ctx.provide(OpenApiSecurityRegistry.class, secRegistry);
        builder.setSecurityRegistry(secRegistry);

        ctx.provide(OpenApiBuilder.class, builder);

        // Collect operation metadata at handler-registration time (no middleware injected)
        ctx.addAnnotationProcessor(handlerClass -> {
            ApiOperation op    = handlerClass.getAnnotation(ApiOperation.class);
            Route        route = handlerClass.getAnnotation(Route.class);
            if (op != null && route != null) {
                builder.addOperation(route, op, handlerClass);
            }
            return java.util.List.of();
        });

        String jsonPath    = basePath + ".json";
        String yamlPath    = basePath + ".yaml";
        String swaggerPath = basePath + "/swagger";

        // JSON spec
        app.get(jsonPath, (req, res) -> {
            res.setContentType(ContentType.JSON);
            return jsonMapper.writeValueAsString(builder.build());
        }).with();

        // YAML spec
        app.get(yamlPath, (req, res) -> {
            res.type(YAML_CONTENT_TYPE);
            return yamlMapper.writeValueAsString(builder.build());
        }).with();

        // Swagger UI — loads from CDN, points at the JSON spec
        String swaggerHtml = buildSwaggerHtml(jsonPath);
        app.get(swaggerPath, (req, res) -> {
            res.setContentType(ContentType.TEXT_HTML);
            return swaggerHtml;
        }).with();
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
}
