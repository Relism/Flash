package dev.relism.flash.ext.openapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import dev.relism.flash.App;
import dev.relism.flash.Bound;
import dev.relism.flash.Module;
import dev.relism.flash.Services;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.DuplexHandler;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.RouteHook;

import java.nio.charset.StandardCharsets;

/**
 * Generates and serves an OpenAPI 3.0 document, and the page that reads it, under a base path.
 *
 * <p>Given {@code basePath = "/openapi"} (the default), three routes are served:
 * <ul>
 *   <li>{@code GET /openapi.json} — the document as JSON</li>
 *   <li>{@code GET /openapi.yaml} — the same document as YAML</li>
 *   <li>{@code GET /openapi/docs} — the {@link Ui}, Swagger UI unless another is chosen</li>
 * </ul>
 *
 * <p>Every route an app builds from a class is documented, annotated or not: a path, the body its
 * handler takes and what it returns are already in the code. {@link ApiOperation} adds the prose,
 * and every {@link OpenApiContributor} the app adds says the rest. The document is written by
 * this module's own mapper, so an application's mapper settings never reshape it.
 *
 * <pre>{@code
 * App.create()
 *     .install(new JsonModule(), new OpenApiModule("/openapi", "My API", "2.0.0").ui(Ui.scalar()));
 * }</pre>
 */
public class OpenApiModule implements Module {

    private static final byte[] YAML_CONTENT_TYPE = "application/yaml".getBytes(StandardCharsets.UTF_8);

    private final String basePath;
    private final String title;
    private final String version;
    private final String description;
    private Ui<?> ui = Ui.swagger();

    public OpenApiModule() {
        this("/openapi", "API", "1.0.0", "");
    }

    public OpenApiModule(String basePath) {
        this(basePath, "API", "1.0.0", "");
    }

    public OpenApiModule(String basePath, String title, String version) {
        this(basePath, title, version, "");
    }

    public OpenApiModule(String basePath, String title, String version, String description) {
        this.basePath    = basePath.endsWith("/") ? basePath.substring(0, basePath.length() - 1) : basePath;
        this.title       = title;
        this.version     = version;
        this.description = description;
    }

    /** Which documentation page to serve, {@link Ui#swagger()} by default; {@link Ui#none()} serves none. */
    public OpenApiModule ui(Ui<?> ui) {
        this.ui = ui;
        return this;
    }

    @Override
    public void install(App app) {
        OpenApiBuilder spec = new OpenApiBuilder().title(title).version(version).description(description);
        ObjectMapper json = JsonMapper.builder().build();
        Document asJson = new Document(json, spec);
        Document asYaml = new Document(new YAMLMapper(), spec);
        String jsonPath = basePath + ".json";

        app.add(OpenApiBuilder.class, spec)
                .add(RouteHook.class, new Collector(spec))
                .add(OpenApiContributor.class, CodecDocs.class)
                .get(jsonPath, (req, res) -> { res.type(ContentType.JSON).body(asJson.bytes()); return null; })
                .get(basePath + ".yaml", (req, res) -> { res.type(YAML_CONTENT_TYPE).body(asYaml.bytes()); return null; });
        if (ui == null) return;
        byte[] page = ui.page(jsonPath, title, json);
        app.get(basePath + "/docs", (req, res) -> { res.type(ContentType.TEXT_HTML).body(page); return null; });
    }

    /** Takes the app's contributors when it starts, then every route it builds from a class. */
    private record Collector(OpenApiBuilder spec) implements RouteHook, Bound {

        @Override
        public void bind(Services services) {
            spec.contributors(services.all(OpenApiContributor.class));
        }

        /**
         * A protocol handler is documented only when a contributor says what it carries, as NDJSON's
         * does. A route for every method is not: OpenAPI 3.0 cannot name methods outside its eight.
         */
        @Override
        public void routed(Route route) {
            if (route.handler() == null || route.method() == null) return;
            if (DuplexHandler.class.isAssignableFrom(route.handler()) && !spec.describes(route.handler())) return;
            spec.addOperation(route, route.handler().getAnnotation(ApiOperation.class), route.handler());
        }
    }

    /**
     * The document encoded once per revision. The first request renders it; from then on the same
     * array is handed out and a request allocates nothing. Two first requests at once encode the
     * same bytes twice, which is cheaper than the lock that would prevent it; the bytes are written
     * before the revision that publishes them, so a reader that sees the revision sees the array.
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
}
