package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.serde.Codec;
import io.avaje.jsonb.Jsonb;

/**
 * JSON for an application, through Avaje Jsonb: provides the {@link Json} codec.
 *
 * <pre>{@code
 * app.install(new JsonModule());
 * }</pre>
 *
 * <p>The default {@link Jsonb} keeps the library's own defaults and loads every adapter the
 * generator wrote. Hand it one of your own to decide otherwise.
 */
public class JsonModule implements Module {

    private final Jsonb jsonb;

    public JsonModule() {
        this(Jsonb.builder().build());
    }

    public JsonModule(Jsonb jsonb) {
        this.jsonb = jsonb;
    }

    @Override
    public void install(App app) {
        app.install(Codec.module(Json.class, new Json(jsonb)));
    }
}
