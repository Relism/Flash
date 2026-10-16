package dev.relism.flash.ext.serde;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * The type arguments a handler class gives a generic base, resolved through every class between
 * them: what a boot-time check or a document reads off a handler's signature.
 *
 * <pre>{@code
 * Type[] types = TypeArguments.of(CreateUser.class, CodecRequestHandler.class); // Json, NewUser, Json, User
 * }</pre>
 */
public final class TypeArguments {

    private TypeArguments() {}

    /** What {@code subclass} binds {@code base}'s type parameters to, in their order. */
    public static Type[] of(Class<?> subclass, Class<?> base) {
        Map<TypeVariable<?>, Type> bound = new HashMap<>();
        for (Class<?> current = subclass; current != null && current != Object.class; ) {
            Type parent = current.getGenericSuperclass();
            if (!(parent instanceof ParameterizedType parameterized)) {
                current = current.getSuperclass();
                continue;
            }
            Class<?> raw = (Class<?>) parameterized.getRawType();
            Type[] actual = parameterized.getActualTypeArguments();
            TypeVariable<?>[] variables = raw.getTypeParameters();
            for (int i = 0; i < variables.length; i++) bound.put(variables[i], resolve(actual[i], bound));
            if (raw == base) {
                Type[] resolved = new Type[actual.length];
                for (int i = 0; i < actual.length; i++) resolved[i] = resolve(actual[i], bound);
                return resolved;
            }
            current = raw;
        }
        throw new IllegalStateException(subclass.getSimpleName() + " leaves " + base.getSimpleName() + " types open");
    }

    /** Whether {@code type} still holds a type variable: a handler class that leaves its types to a subclass. */
    public static boolean open(Type type) {
        if (type instanceof TypeVariable<?>) return true;
        if (type instanceof ParameterizedType parameterized)
            for (Type argument : parameterized.getActualTypeArguments()) if (open(argument)) return true;
        return false;
    }

    private static Type resolve(Type type, Map<TypeVariable<?>, Type> bound) {
        while (type instanceof TypeVariable<?> variable && bound.containsKey(variable)) type = bound.get(variable);
        if (type instanceof ParameterizedType parameterized) {
            Type[] actual = parameterized.getActualTypeArguments();
            Type[] resolved = new Type[actual.length];
            boolean changed = false;
            for (int i = 0; i < actual.length; i++) {
                resolved[i] = resolve(actual[i], bound);
                changed |= resolved[i] != actual[i];
            }
            if (changed) return new ResolvedType((Class<?>) parameterized.getRawType(), resolved,
                    parameterized.getOwnerType());
        }
        return type;
    }

    private record ResolvedType(Class<?> raw, Type[] arguments, Type owner) implements ParameterizedType {
        @Override public Type[] getActualTypeArguments() { return arguments.clone(); }
        @Override public Type getRawType() { return raw; }
        @Override public Type getOwnerType() { return owner; }
        @Override public boolean equals(Object other) {
            return other instanceof ParameterizedType type
                    && Objects.equals(owner, type.getOwnerType())
                    && raw.equals(type.getRawType())
                    && Arrays.equals(arguments, type.getActualTypeArguments());
        }
        @Override public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(owner) ^ raw.hashCode();
        }
        @Override public String getTypeName() {
            StringBuilder name = new StringBuilder(raw.getTypeName()).append('<');
            for (int i = 0; i < arguments.length; i++)
                name.append(i == 0 ? "" : ", ").append(arguments[i].getTypeName());
            return name.append('>').toString();
        }
    }
}
