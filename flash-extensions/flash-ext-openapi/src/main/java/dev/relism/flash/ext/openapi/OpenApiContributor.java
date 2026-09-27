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

    /** The name {@code field} has on the wire, or null to leave it to another contributor or the field. */
    default String name(Field field) {
        return null;
    }

    /** Whether {@code field} is left out of what is written. */
    default boolean ignored(Field field) {
        return false;
    }

    /**
     * Adds to one property's schema what the code already says elsewhere — a validation engine's
     * rules, a format's read-only or write-only access. Returns true when the property is required.
     *
     * <p>Called while a type's schema is built, once per document revision.
     */
    default boolean property(Field field, Map<String, Object> schema) {
        return false;
    }
}
