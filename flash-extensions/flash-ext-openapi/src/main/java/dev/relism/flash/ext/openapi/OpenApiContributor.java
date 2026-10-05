package dev.relism.flash.ext.openapi;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Map;

public interface OpenApiContributor {

    /**
     * Describes {@code type} completely, or returns {@code null} to leave it to the next
     * contributor and the default Java model. Generated formats use this instead of exposing
     * implementation fields that never appear on their wire.
     */
    default Map<String, Object> schema(Type type) {
        return null;
    }

    /**
     * Told of every type a schema is asked for, whichever contributor describes it. Lets a
     * contributor that only adds to another's schemas, through {@link #componentContributions()},
     * know which types are in the document.
     */
    default void used(Type type) {}

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
