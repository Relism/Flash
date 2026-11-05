package dev.relism.flash.ext.serde;

import dev.relism.flash.Services;
import dev.relism.flash.http.Request;

import java.lang.reflect.Type;
import java.util.function.Consumer;

/** A decoder already bound to one concrete type. */
@FunctionalInterface
public interface Decoder {
    Object decode(byte[] bytes, int offset, int length) throws Exception;

    default Object decode(Request request) throws Exception {
        byte[] bytes = request.body().bytes();
        return decode(bytes, 0, bytes.length);
    }

    /**
     * {@code codec}'s decoder of {@code type}, passing what it decodes to every installed {@link Check}
     * that checks the type: how a protocol takes a decoder. The codec's own when nothing checks the type.
     * For boot only.
     */
    static Decoder of(Codec codec, Type type, Services services) {
        Decoder decoder = codec.decoder(type);
        Consumer<Object> checks = null;
        for (Check check : services.all(Check.class)) {
            Consumer<Object> one = check.of(type);
            if (one != null) checks = checks == null ? one : checks.andThen(one);
        }
        if (checks == null) return decoder;
        Consumer<Object> checked = checks;
        return new Decoder() {
            @Override public Object decode(byte[] bytes, int offset, int length) throws Exception {
                Object value = decoder.decode(bytes, offset, length);
                checked.accept(value);
                return value;
            }
            @Override public Object decode(Request request) throws Exception {
                Object value = decoder.decode(request);
                checked.accept(value);
                return value;
            }
        };
    }
}
