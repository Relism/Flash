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
 * What a Jackson format writes a field as, told to the published document: its name, as the codec's
 * mapper writes it ({@code @JsonProperty}, then the naming strategy), whether it is there at all
 * ({@code @JsonIgnore}, {@code @JsonIgnoreProperties}), and its direction ({@code access}).
 */
public final class JacksonDocs {

    private JacksonDocs() {}

    /** The contributor of {@code codec}, when flash-ext-openapi is on the classpath; nothing otherwise. */
    public static Module module(JacksonCodec codec) {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, new Fields(codec)));
    }

    /** Loaded only through {@link #module}, so a format works without flash-ext-openapi. */
    static final class Fields implements OpenApiContributor {

        private final JacksonCodec codec;

        Fields(JacksonCodec codec) {
            this.codec = codec;
        }

        /** The codec's own name for the field, so the document and the wire agree. */
        @Override
        public String name(Field field) {
            return codec.wireName(field);
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
