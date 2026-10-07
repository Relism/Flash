package dev.relism.flash.ext.serde;

import dev.relism.flash.Services;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.util.HashMap;
import java.util.Map;
import java.util.Arrays;
import java.util.Objects;

/** Boot-only resolution shared by the two public handler bases. */
final class CodecBinding {
    final Codec in;
    final Type inType;
    final Codec out;
    final Type outType;

    private CodecBinding(Codec in, Type inType, Codec out, Type outType) {
        this.in = in;
        this.inType = inType;
        this.out = out;
        this.outType = outType;
    }

    static CodecBinding resolve(Services services, Class<?> handler, Class<?> base) {
        Type[] arguments = arguments(handler, base);
        return new CodecBinding(services.get(codec(arguments[0])), concrete(arguments[1], handler),
                services.get(codec(arguments[2])), concrete(arguments[3], handler));
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends Codec> codec(Type type) {
        if (type instanceof Class<?> raw && Codec.class.isAssignableFrom(raw))
            return (Class<? extends Codec>) raw;
        throw new IllegalStateException("codec type is left open: " + type.getTypeName());
    }

    private static Type concrete(Type type, Class<?> handler) {
        if (open(type))
            throw new IllegalStateException(handler.getSimpleName() + " leaves its message types open");
        return type;
    }

    private static boolean open(Type type) {
        if (type instanceof TypeVariable<?>) return true;
        if (type instanceof ParameterizedType parameterized)
            for (Type argument : parameterized.getActualTypeArguments()) if (open(argument)) return true;
        return false;
    }

    static Type[] arguments(Class<?> subclass, Class<?> base) {
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
