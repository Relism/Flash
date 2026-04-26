package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.routing.Middleware;

/**
 * Registers JSON support into the Flash extension layer.
 *
 * <p>Exposes a {@link Json} utility instance in the {@link FlashContext} under
 * {@code Json.class}. Any handler or extension can retrieve it via {@code ctx.require(Json.class)}
 * inside {@code onInit()} (class-based) or inside {@link FlashExtension#routes} (extensions).
 *
 * <p>The raw {@link ObjectMapper} is also registered under {@code ObjectMapper.class}
 * for extensions that need direct mapper access (e.g. OpenAPI schema generation).
 *
 * <p>{@link JacksonMiddleware} is provided under {@code JacksonMiddleware.class} and
 * exposes opinionated JSON auto-marshalling middleware via {@link JacksonMiddleware#autoJson()}.
 *
 * <h3>Usage — composition (preferred)</h3>
 * <pre>{@code
 * public class MyHandler extends RequestHandler {
 *     private Json json;
 *
 *     @Override protected void onInit() {
 *         json = require(Json.class);
 *     }
 *
 *     public Object handle(Request req, Response res) throws Exception {
 *         MyDto dto = json.body(req, MyDto.class);
 *         return json.write(res, 201, dto);
 *     }
 * }
 * }</pre>
 *
 * <h3>Custom mapper</h3>
 * <pre>{@code
 * ObjectMapper mapper = JsonMapper.builder()
 *         .addModule(new JavaTimeModule())
 *         .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
 *         .build();
 *
 * FlashApp.create(8080)
 *         .install(new JacksonExtension(mapper));
 * }</pre>
 */
public class JacksonExtension implements FlashExtension {

    private final ObjectMapper mapper;
    private final JacksonMiddleware middleware;

    /**
     * Installs with an opinionated default {@link JsonMapper}:
     * auto-discovers modules on classpath (e.g. Java Time) and writes dates as ISO strings.
     */
    public JacksonExtension() {
        this(JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());
    }

    /** Installs with a fully configured custom {@link ObjectMapper}. */
    public JacksonExtension(ObjectMapper mapper) {
        this.mapper = mapper;
        this.middleware = new JacksonMiddleware(mapper);
    }

    /**
     * Opinionated outbound JSON middleware factory.
     *
     * <p>Use for app/scope-level registration:
     * <pre>{@code
     * JacksonExtension jackson = new JacksonExtension();
     * app.install(jackson).use(jackson.autoJson());
     * }</pre>
     */
    public Middleware autoJson() {
        return middleware.autoJson();
    }

    @Override
    public void provide(FlashContext ctx) {
        Json json = new Json(mapper);
        ctx.provide(Json.class,               json);
        ctx.provide(ObjectMapper.class,       mapper);
        ctx.provide(JacksonMiddleware.class,  middleware);
    }
}
