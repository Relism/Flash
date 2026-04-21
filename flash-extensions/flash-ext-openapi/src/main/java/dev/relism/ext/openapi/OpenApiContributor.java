package dev.relism.ext.openapi;

import java.util.Map;

public interface OpenApiContributor {

    default Map<String, Object> componentContributions() {
        return Map.of();
    }

    default OpenApiOperationContribution operationFor(Class<?> handlerClass) {
        return OpenApiOperationContribution.empty();
    }
}
