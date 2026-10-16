package dev.relism.flash.ext.ndjson;

import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.ext.serde.TypeArguments;
import dev.relism.flash.http.ContentType;
import java.lang.reflect.Type;

/**
 * Documents an {@link NdjsonHandler}: {@code application/x-ndjson} each way it streams, with the
 * schema of one line. OpenAPI 3.0 has no standard way to say "a sequence of these"; the element's
 * schema under the media type is the common convention. Installed by {@link NdjsonModule}.
 */
final class NdjsonDocs implements OpenApiContributor {

    @Override
    public OpenApiOperationContribution operationFor(Class<?> handler) {
        if (!NdjsonHandler.class.isAssignableFrom(handler)) return OpenApiOperationContribution.empty();
        Type[] types = TypeArguments.of(handler, NdjsonHandler.class);
        boolean reads = types[0] != Void.class;
        boolean writes = types[1] != Void.class;
        return OpenApiOperationContribution.builder()
                .consumes(reads ? ContentType.NDJSON : null)
                .produces(writes ? ContentType.NDJSON : null)
                .requestType(reads ? types[0] : null)
                .responseType(writes ? types[1] : null)
                .build();
    }
}
