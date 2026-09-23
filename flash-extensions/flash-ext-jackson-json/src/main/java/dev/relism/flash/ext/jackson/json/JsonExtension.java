package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.relism.flash.ext.jackson.Marshalling;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.extension.FlashExtension;
import dev.relism.flash.extension.FlashRegistrar;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.routing.Middleware;

/**
 * JSON for an application: the {@link Json} codec, the mapper behind it, and the middleware that
 * serializes whatever a handler returns.
 *
 * <pre>{@code
 * JsonExtension json = new JsonExtension();
 * app.install(json).use(json.auto());
 * }</pre>
 *
 * <p>The default mapper discovers the modules on the classpath (Java Time among them) and writes
 * dates as ISO strings. Hand it a mapper of your own to decide otherwise.
 */
public class JsonExtension implements FlashExtension {

    private final ObjectMapper mapper;

    public JsonExtension() {
        this(JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());
    }

    public JsonExtension(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** Serializes what a handler returns, unless it already returned a response, bytes or text. */
    public Middleware auto() {
        return Marshalling.of(mapper, ContentType.JSON);
    }

    @Override
    public void configure(FlashRegistrar<?> app, FlashContext ctx) {
        ctx.provide(Json.class, new Json(mapper));
        ctx.provide(ObjectMapper.class, mapper);
    }
}
