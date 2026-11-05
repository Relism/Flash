package dev.relism.flash.ext.serde;

import dev.relism.flash.Module;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.Response;
import java.lang.reflect.Field;
import java.lang.reflect.Type;

/**
 * One serialization format, prepared for concrete types at boot. A format's module adds its codec
 * through {@link #module}.
 */
public interface Codec {
    /** What parses {@code type}, prepared now. A protocol takes it through {@link Decoder#of}, which checks what it parses. */
    Decoder decoder(Type type);
    Encoder encoder(Type type);
    Object write(Response response, Object value) throws Exception;
    ContentType contentType();

    /**
     * The name {@code field} has on this format's wire, or null when the format does not carry it:
     * for whatever matches names a client sends, and for the published document. Asked at boot,
     * never per request. The Java name by default.
     */
    default String wireName(Field field) {
        return field.getName();
    }

    /**
     * Adds {@code codec} under {@code type}, which handlers name in their signature, and as a
     * {@code Codec}, which is how the app knows its formats; with flash-ext-openapi on the classpath,
     * the handlers of every codec are documented from their signature.
     */
    static <C extends Codec> Module module(Class<C> type, C codec) {
        return app -> app.add(type, codec).add(Codec.class, codec).install(Module.ifPresent(
                "dev.relism.flash.ext.openapi.OpenApiContributor", () -> docs -> docs.add(OpenApiContributor.class, CodecDocs.class)));
    }
}
