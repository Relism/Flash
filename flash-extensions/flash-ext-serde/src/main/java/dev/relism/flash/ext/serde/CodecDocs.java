package dev.relism.flash.ext.serde;

import dev.relism.flash.Module;
import dev.relism.flash.Services;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.TypedHandler;

import java.lang.reflect.Type;

/**
 * Tells the published document what a {@link CodecHandler} reads and writes, by asking its codecs.
 * The media type documented is the one the codec that runs reports, so nothing is declared twice.
 *
 * <p>A format module installs {@link #module()}; the first one adds the contributor and the rest
 * add nothing, since adding the same class twice adds it once.
 */
public final class CodecDocs {

    private CodecDocs() {}

    /** The contributor, when flash-ext-openapi is on the classpath; nothing otherwise. */
    public static Module module() {
        return Module.ifPresent("dev.relism.flash.ext.openapi.OpenApiContributor",
                () -> app -> app.add(OpenApiContributor.class, MediaTypes.class));
    }

    /** Loaded only through {@link #module()}, so this module works without flash-ext-openapi. */
    static final class MediaTypes implements OpenApiContributor {

        private final Services services;

        MediaTypes(Services services) {
            this.services = services;
        }

        @Override
        public OpenApiOperationContribution operationFor(Class<?> handler) {
            if (!CodecHandler.class.isAssignableFrom(handler)) return OpenApiOperationContribution.empty();
            return OpenApiOperationContribution.builder()
                    .consumes(mediaType(handler, 0))
                    .produces(mediaType(handler, 2))
                    .build();
        }

        /** The content type of the codec a handler names at {@code index}, or null when it names none. */
        private ContentType mediaType(Class<?> handler, int index) {
            if (index == 0 && TypedHandler.typeArgument(handler, CodecHandler.class, 1) == Void.class) return null;
            Type argument = TypedHandler.typeArgument(handler, CodecHandler.class, index);
            if (!(argument instanceof Class<?> type) || !Codec.class.isAssignableFrom(type)) return null;
            return ((Codec) services.get(type)).contentType();
        }
    }
}
