package dev.relism.flash.ext.openapi;

import dev.relism.flash.Services;
import dev.relism.flash.ext.serde.Codec;
import dev.relism.flash.ext.serde.CodecRequestHandler;
import dev.relism.flash.ext.serde.TypeArguments;
import dev.relism.flash.http.ContentType;
import java.lang.reflect.Type;

/**
 * Tells the document what a {@code CodecRequestHandler} reads and writes: the two types from its signature,
 * and the media type each codec reports. Nothing is declared twice, and nothing is guessed from the
 * handler's methods. Installed by {@link OpenApiModule}.
 */
final class CodecDocs implements OpenApiContributor {

    private final Services services;

    CodecDocs(Services services) {
        this.services = services;
    }

    @Override
    public OpenApiOperationContribution operationFor(Class<?> handler) {
        if (!CodecRequestHandler.class.isAssignableFrom(handler)) return OpenApiOperationContribution.empty();
        Type[] signature = TypeArguments.of(handler, CodecRequestHandler.class);
        boolean reads = signature[1] != Void.class;
        return OpenApiOperationContribution.builder()
                .consumes(reads ? contentType(signature[0]) : null)
                .produces(contentType(signature[2]))
                .requestType(reads ? signature[1] : null)
                .responseType(signature[3])
                .build();
    }

    @SuppressWarnings("unchecked")
    private ContentType contentType(Type codec) {
        return services.get((Class<? extends Codec>) codec).contentType();
    }
}
