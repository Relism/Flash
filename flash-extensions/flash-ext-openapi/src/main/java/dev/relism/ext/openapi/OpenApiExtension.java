package dev.relism.ext.openapi;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;
import dev.relism.extension.FlashRegistrar;
import dev.relism.http.ContentType;
import dev.relism.http.HttpMethod;
import dev.relism.routing.Route;

import java.lang.annotation.Annotation;

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
 * <p>Operations are collected at boot from handlers annotated with {@link ApiOperation}
 * that also have route metadata ({@link Route} or shorthand verb annotations).
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
    public void provide(FlashContext ctx) {
        OpenApiBuilder          builder     = new OpenApiBuilder().title(title).version(version).description(description);
        OpenApiSecurityRegistry secRegistry = new OpenApiSecurityRegistry();

        ctx.provide(OpenApiSecurityRegistry.class, secRegistry);
        ctx.provide(OpenApiBuilder.class,          builder);
        builder.setSecurityRegistry(secRegistry);

        // Collect operation metadata at handler-registration time (no middleware injected).
        ctx.addAnnotationProcessor(handlerClass -> {
            ApiOperation op    = handlerClass.getAnnotation(ApiOperation.class);
            Route        route = routeOf(handlerClass);
            if (op != null && route != null) builder.addOperation(route, op, handlerClass);
            return java.util.List.of();
        });
    }

    @Override
    public void routes(FlashRegistrar<?> app, FlashContext ctx) {
        ObjectMapper jsonMapper = ctx.find(ObjectMapper.class).orElseGet(() -> JsonMapper.builder().build());
        YAMLMapper   yamlMapper = new YAMLMapper();
        OpenApiBuilder builder  = ctx.require(OpenApiBuilder.class);

        String jsonPath    = basePath + ".json";
        String yamlPath    = basePath + ".yaml";
        String swaggerPath = basePath + "/swagger";
        String swaggerHtml = buildSwaggerHtml(jsonPath);

        app.get(jsonPath,    (req, res) -> { res.type(ContentType.JSON);     return jsonMapper.writeValueAsString(builder.build()); });
        app.get(yamlPath,    (req, res) -> { res.type(YAML_CONTENT_TYPE);              return yamlMapper.writeValueAsString(builder.build()); });
        app.get(swaggerPath, (req, res) -> { res.type(ContentType.TEXT_HTML); return swaggerHtml; });
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

    private static Route routeOf(Class<?> cls) {
        Route direct = cls.getAnnotation(Route.class);
        if (direct != null) return direct;
        for (Annotation ann : cls.getAnnotations()) {
            Route meta = ann.annotationType().getAnnotation(Route.class);
            if (meta == null) continue;
            String path = readPathValue(ann);
            if (path == null) continue;
            HttpMethod method = meta.method();
            return new Route() {
                @Override public HttpMethod method() { return method; }
                @Override public String path() { return path; }
                @Override public Class<? extends Annotation> annotationType() { return Route.class; }
            };
        }
        return null;
    }

    private static String readPathValue(Annotation ann) {
        try {
            Object v = ann.annotationType().getMethod("value").invoke(ann);
            return v instanceof String s ? s : null;
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }
}
