package dev.relism.flash.ext.serde;

import dev.relism.flash.Services;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Tells the published document what the codecs say. A {@code CodecRequestHandler}'s two types come
 * from its signature and each end's media type from its codec; an input that is {@link FromRequest}
 * is left to whoever describes its parameters, with its codec's names. A schema is shared by every
 * codec: a field is named by the first codec that renames it, and left out only when no codec carries
 * it. Installed by {@link Codec#module}, and loaded only when flash-ext-openapi is there.
 */
final class CodecDocs implements OpenApiContributor {

    private final List<Codec> codecs;
    private final Services services;

    CodecDocs(List<Codec> codecs, Services services) {
        this.codecs = codecs;
        this.services = services;
    }

    @Override
    public OpenApiOperationContribution operationFor(Class<?> handler) {
        if (!CodecRequestHandler.class.isAssignableFrom(handler)) return OpenApiOperationContribution.empty();
        Type[] signature = TypeArguments.of(handler, CodecRequestHandler.class);
        boolean parameters = CodecBinding.fromRequest(signature[1]);
        boolean body = !parameters && signature[1] != Void.class;
        OpenApiOperationContribution.Builder operation = OpenApiOperationContribution.builder()
                .consumes(body ? codec(signature[0]).contentType() : null)
                .produces(codec(signature[2]).contentType())
                .requestType(body ? signature[1] : null)
                .responseType(signature[3]);
        // Named as the binder reads it: with the input codec's names.
        if (parameters) operation.requestParameters(signature[1], codec(signature[0])::wireName);
        return operation.build();
    }

    @Override
    public String name(Field field) {
        String name = wireName(field);
        return name == null || name.equals(field.getName()) ? null : name;
    }

    @Override
    public boolean ignored(Field field) {
        return wireName(field) == null;
    }

    /** The first rename among the codecs that carry the field, its Java name, or null when none carries it. */
    private String wireName(Field field) {
        String name = null;
        for (Codec codec : codecs) {
            String said = codec.wireName(field);
            if (said == null) continue;
            if (!said.equals(field.getName())) return said;
            name = said;
        }
        return name;
    }

    @SuppressWarnings("unchecked")
    private Codec codec(Type codec) {
        return services.get((Class<? extends Codec>) codec);
    }
}
