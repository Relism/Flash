package dev.relism.flash.ext.avaje.jsonb;

import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * What Jsonb writes a type as, told to the published document: each field under its wire name
 * ({@code @Json.Property}), and none that is not written ({@code @Json.Ignore}).
 *
 * <p>Jsonb's annotations exist only at compile time, so they are not read here: the generated
 * adapter is, through {@link Json#wireName}.
 */
public final class JsonbDocs {

    private JsonbDocs() {}

    /** The contributor, when flash-ext-openapi is on the classpath; nothing otherwise. */
    public static Module module() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, Fields.class));
    }

    /** Loaded only through {@link #module()}, so this format works without flash-ext-openapi. */
    static final class Fields implements OpenApiContributor {

        private final Json json;

        Fields(Json json) {
            this.json = json;
        }

        @Override
        public String name(Field field) {
            Map<String, String> names = json.written(field.getDeclaringClass());
            return names == null ? null : names.get(field.getName());
        }

        @Override
        public boolean ignored(Field field) {
            Map<String, String> names = json.written(field.getDeclaringClass());
            return names != null && !names.containsKey(field.getName());
        }
    }
}
