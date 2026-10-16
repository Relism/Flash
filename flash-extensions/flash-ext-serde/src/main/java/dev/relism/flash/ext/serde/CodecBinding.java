package dev.relism.flash.ext.serde;

import dev.relism.flash.Services;
import java.lang.reflect.Type;

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
        Type[] arguments = TypeArguments.of(handler, base);
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
        if (TypeArguments.open(type))
            throw new IllegalStateException(handler.getSimpleName() + " leaves its message types open");
        return type;
    }
}
