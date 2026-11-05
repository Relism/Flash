package dev.relism.flash.ext.serde;

import dev.relism.flash.Services;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.http.ContentType;
import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.List;

/**
 * Tells the published document what the codecs say. A {@code CodecRequestHandler}'s two types come
 * from its signature and each end's media type from its codec; an input that is {@link FromRequest}
 * is left to whoever describes its parameters. A field is named by the first codec that renames it
 * and left out by the first that does not carry it. Installed by {@link Codec#module}, and loaded
 * only when flash-ext-openapi is there.
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
        return OpenApiOperationContribution.builder()
                .consumes(body ? contentType(signature[0]) : null)
                .produces(contentType(signature[2]))
                .requestType(body ? signature[1] : null)
                .requestParameters(parameters ? signature[1] : null)
                .responseType(signature[3])
                .build();
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

    /** The first codec's name that is not the Java one, null when one does not carry the field. */
    private String wireName(Field field) {
        for (Codec codec : codecs) {
            String name = codec.wireName(field);
            if (name == null || !name.equals(field.getName())) return name;
        }
        return field.getName();
    }

    @SuppressWarnings("unchecked")
    private ContentType contentType(Type codec) {
        return services.get((Class<? extends Codec>) codec).contentType();
    }
}
