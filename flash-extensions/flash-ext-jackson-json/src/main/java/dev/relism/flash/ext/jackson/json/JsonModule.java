package dev.relism.flash.ext.jackson.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.relism.flash.ext.jackson.JacksonDocs;
import dev.relism.flash.ext.serde.CodecDocs;
import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.http.ContentType;

/**
 * JSON for an application: the {@link Json} codec over this module's mapper.
 *
 * <pre>{@code
 * app.install(new JsonModule());
 * }</pre>
 *
 * <p>The default mapper discovers the modules on the classpath (Java Time among them) and writes
 * dates as ISO strings. Hand it a mapper of your own to decide otherwise.
 */
public class JsonModule implements Module {

    private final ObjectMapper mapper;

    public JsonModule() {
        this(JsonMapper.builder()
                .findAndAddModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .build());
    }

    public JsonModule(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void install(App app) {
        app.add(Json.class, new Json(mapper)).install(CodecDocs.module(), JacksonDocs.module());
    }
}
