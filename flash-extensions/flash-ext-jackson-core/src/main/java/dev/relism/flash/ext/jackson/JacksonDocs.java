package dev.relism.flash.ext.jackson;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Map;

/**
 * What Jackson's annotations say a field is on the wire, told to the published document: its name
 * ({@code @JsonProperty}), whether it is there at all ({@code @JsonIgnore},
 * {@code @JsonIgnoreProperties}), and its direction ({@code access}).
 */
public final class JacksonDocs {

    private JacksonDocs() {}

    /** The contributor, when flash-ext-openapi is on the classpath; nothing otherwise. */
    public static Module module() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, Fields.class));
    }

    /** Loaded only through {@link #module()}, so a format works without flash-ext-openapi. */
    static final class Fields implements OpenApiContributor {

        @Override
        public String name(Field field) {
            JsonProperty property = field.getAnnotation(JsonProperty.class);
            return property == null || property.value().isEmpty() ? null : property.value();
        }

        @Override
        public boolean ignored(Field field) {
            JsonIgnore ignore = field.getAnnotation(JsonIgnore.class);
            if (ignore != null && ignore.value()) return true;
            JsonIgnoreProperties type = field.getDeclaringClass().getAnnotation(JsonIgnoreProperties.class);
            return type != null && Arrays.asList(type.value()).contains(field.getName());
        }

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
