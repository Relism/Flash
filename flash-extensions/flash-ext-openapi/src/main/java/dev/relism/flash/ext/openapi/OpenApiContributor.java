package dev.relism.flash.ext.openapi;

import java.lang.reflect.Field;
import java.util.Map;

public interface OpenApiContributor {

    default Map<String, Object> componentContributions() {
        return Map.of();
    }

    default OpenApiOperationContribution operationFor(Class<?> handlerClass) {
        return OpenApiOperationContribution.empty();
    }

    /**
     * Adds to one property's schema what the code already says elsewhere — a validation engine's
     * rules, above all. Returns true when the property is required.
     *
     * <p>Called while a type's schema is built, once per document revision.
     */
    default boolean property(Field field, Map<String, Object> schema) {
        return false;
    }
}
