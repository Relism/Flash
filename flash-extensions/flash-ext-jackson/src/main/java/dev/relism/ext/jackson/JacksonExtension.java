package dev.relism.ext.jackson;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.relism.extension.FlashContext;
import dev.relism.extension.FlashExtension;
import dev.relism.extension.FlashRegistrar;

/**
 * Registers JSON support into the Flash extension layer.
 *
 * <p>Exposes a {@link Json} utility instance in the {@link FlashContext} under
 * {@code Json.class}. Any handler or extension in the same scope can retrieve
 * it via {@code require(Json.class)} inside {@code onInit()}.
 *
 * <p>The raw {@link ObjectMapper} is also registered under {@code ObjectMapper.class}
 * for extensions that need direct mapper access (e.g. OpenAPI schema generation).
 *
 * <h3>Usage — composition (preferred)</h3>
 * <pre>{@code
 * // No mandatory base class. Works from any RequestHandler.
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
 * <h3>Usage — convenience base class</h3>
 * <pre>{@code
 * // JacksonHandler remains available as a thin opt-in wrapper.
 * public class MyHandler extends JacksonHandler {
 *     public Object handle(Request req, Response res) throws Exception {
 *         return json(res, service.findAll());
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

    /** Installs with a default {@link JsonMapper} (no extra modules). */
    public JacksonExtension() {
        this(JsonMapper.builder().build());
    }

    /** Installs with a fully configured custom {@link ObjectMapper}. */
    public JacksonExtension(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void install(FlashRegistrar app, FlashContext ctx) {
        Json json = new Json(mapper);
        ctx.provide(Json.class,         json);
        ctx.provide(ObjectMapper.class, mapper);   // backward compat for extensions (OpenAPI, etc.)
    }
}
