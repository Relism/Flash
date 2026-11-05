package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.annotation.JsonProperty;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * What a Jackson format says of a field beyond its name, told to the published document: its
 * direction ({@code access}) and whether it is required. Names and exclusions come from the codec,
 * through {@code Codec.wireName}.
 */
public final class JacksonDocs {

    private JacksonDocs() {}

    /** The contributor, when flash-ext-openapi is on the classpath; nothing otherwise. */
    public static Module module() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, Access.class));
    }

    /** Loaded only through {@link #module}, so a format works without flash-ext-openapi. */
    static final class Access implements OpenApiContributor {

        @Override
        public boolean property(Field field, Map<String, Object> schema) {
            JsonProperty property = field.getAnnotation(JsonProperty.class);
            if (property == null) return false;
            if (property.access() == JsonProperty.Access.READ_ONLY) schema.put("readOnly", true);
            if (property.access() == JsonProperty.Access.WRITE_ONLY) schema.put("writeOnly", true);
            return property.required();
        }
    }
}
