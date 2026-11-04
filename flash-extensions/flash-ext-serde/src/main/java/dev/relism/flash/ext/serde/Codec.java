package dev.relism.flash.ext.serde;

import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

/** One serialization format, prepared for concrete types at boot. */
public interface Codec {
    default <T> T read(Request request, Type type) throws Exception {
        @SuppressWarnings("unchecked")
        T value = (T) decoder(type).decode(request);
        return value;
    }
    Decoder decoder(Type type);
    Encoder encoder(Type type);
    Object write(Response response, Object value) throws Exception;
    ContentType contentType();

    /**
     * The name {@code field} has on this format's wire, for whatever matches names a client sends
     * against a type's fields. Asked at boot, never per request. The Java name by default.
     */
    default String wireName(Field field) {
        return field.getName();
    }
}
