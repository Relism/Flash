package dev.relism.flash.ext.data;

import dev.relism.flash.Module;
import dev.relism.flash.ext.data.core.Page;
import dev.relism.flash.ext.data.core.PageRequest;
import dev.relism.flash.ext.data.core.Pager;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.ext.openapi.OpenApiResponseContribution;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.InputBinder;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * What paging adds to flash-ext-serde and flash-ext-openapi when they are on the classpath: a
 * {@code PageRequest<T>} read from the query of a typed handler, and its parameters wherever the
 * document meets one. Each nested class is loaded only by its {@link Module#ifPresent}, so data-core needs neither.
 */
final class PageModules {

    private PageModules() {}

    static Module serde() {
        return Module.ifPresent("dev.relism.flash.ext.serde.InputBinder",
                () -> app -> app.add(InputBinder.class, Input.class));
    }

    static Module openapi() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, Docs.class));
    }

    /** The entity of a {@code PageRequest<T>}, or null for any other type. */
    static Class<?> paged(Type type) {
        if (type == PageRequest.class)
            throw new IllegalStateException("PageRequest names the type it pages: PageRequest<User>");
        if (!(type instanceof ParameterizedType p) || p.getRawType() != PageRequest.class) return null;
        if (p.getActualTypeArguments()[0] instanceof Class<?> entity) return entity;
        throw new IllegalStateException("PageRequest pages a class, not " + p.getActualTypeArguments()[0].getTypeName());
    }

    /** A handler's {@code PageRequest<T>}, read by a pager that names fields as the handler's input codec does. */
    static final class Input implements InputBinder {
        @Override
        public Reader reader(Type type, Codec codec) {
            Class<?> entity = paged(type);
            if (entity == null) return null;
            return Pager.of(entity, codec::wireName)::read;
        }
    }

    /** {@code size}, {@code sort} and {@code after} on every operation that takes a {@code PageRequest}. */
    static final class Docs implements OpenApiContributor {

        @Override
        public OpenApiOperationContribution parameters(Type type, Function<Field, String> names) {
            Class<?> entity = paged(type);
            if (entity == null) return null;
            Pager<?> pager = Pager.of(entity, names);
            return OpenApiOperationContribution.builder()
                    .parameter(query("size", "How many items the page holds", Map.of(
                            "type", "integer", "minimum", 1, "maximum", Pager.MAX_SIZE, "default", Pager.DEFAULT_SIZE)))
                    .parameter(query("sort", "The order of the pages, - reverses it", Map.of(
                            "type", "string", "enum", pager.sorts(), "default", pager.sorts().get(0))))
                    .parameter(query("after", "The next of the previous page; absent for the first", Map.of(
                            "type", "string")))
                    .response(400, OpenApiResponseContribution.builder()
                            .description("Invalid size, sort or after").build())
                    .build();
        }

        @Override
        public boolean property(Field field, Map<String, Object> schema) {
            if (field.getDeclaringClass() != Page.class) return false;
            if (field.getName().equals("next")) schema.put("nullable", true);
            return field.getName().equals("content");
        }

        private static Map<String, Object> query(String name, String description, Map<String, Object> schema) {
            Map<String, Object> parameter = new LinkedHashMap<>();
            parameter.put("name", name);
            parameter.put("in", "query");
            parameter.put("required", false);
            parameter.put("description", description);
            parameter.put("schema", new LinkedHashMap<>(schema));
            return parameter;
        }
    }
}
