package dev.relism.flash.ext.validation;

import dev.relism.flash.ext.serde.Check;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Hands each type a codec decodes to the one engine that checks it; a collection or an array goes
 * by its element, and an array reaches the engine as a list. Installed by {@link Validation#module},
 * and loaded only when flash-ext-serde is there.
 */
final class ValidationCheck implements Check {

    private final List<Validation> engines;

    ValidationCheck(List<Validation> engines) {
        this.engines = engines;
    }

    /** @throws IllegalStateException when two engines check the type, naming both */
    @Override
    public Consumer<Object> of(Type type) {
        Class<?> checked = element(type);
        Validation found = null;
        for (Validation engine : engines) {
            if (!engine.checks(checked)) continue;
            if (found != null) throw new IllegalStateException(found.getClass().getName() + " and "
                    + engine.getClass().getName() + " both check " + checked.getName() + ": a type is checked by one engine");
            found = engine;
        }
        if (found == null) return null;
        Validation engine = found;
        // Engines walk a collection element by element; an array is handed over as one.
        return array(type) ? value -> engine.verify(value instanceof Object[] items ? Arrays.asList(items) : value) : engine::verify;
    }

    private static boolean array(Type type) {
        return type instanceof GenericArrayType || type instanceof Class<?> c && c.isArray();
    }

    /** What the values of {@code type} are checked as: {@code User} for a {@code List<User>} or a {@code User[]}. */
    private static Class<?> element(Type type) {
        if (type instanceof ParameterizedType p && p.getRawType() instanceof Class<?> raw)
            return Collection.class.isAssignableFrom(raw) ? element(p.getActualTypeArguments()[0]) : raw;
        if (type instanceof GenericArrayType array) return element(array.getGenericComponentType());
        if (type instanceof Class<?> c) return c.isArray() ? c.getComponentType() : c;
        return Object.class;
    }
}
