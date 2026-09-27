package dev.relism.flash.ext.security;

import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.ext.openapi.OpenApiResponseContribution;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The security schemes and each operation's requirements, in the published document. Installed by
 * {@link SecurityModule} only when flash-ext-openapi is on the classpath.
 */
final class SecurityDocs implements OpenApiContributor {

    private final Security security;

    SecurityDocs(Security security) {
        this.security = security;
    }

    @Override
    public Map<String, Object> componentContributions() {
        Map<String, Object> definitions = new LinkedHashMap<>();
        for (SecurityScheme scheme : security.schemes()) definitions.put(scheme.name(), scheme.definition());
        return definitions.isEmpty() ? Map.of() : Map.of("securitySchemes", definitions);
    }

    @Override
    public OpenApiOperationContribution operationFor(Class<?> handler) {
        SecurityPolicy policy = SecurityPolicy.of(handler);
        if (policy == null || !policy.required) return OpenApiOperationContribution.empty();
        OpenApiOperationContribution.Builder operation = OpenApiOperationContribution.builder();
        for (SecurityScheme scheme : security.schemes()) {
            operation.security(scheme.name(), scheme.issuer() != null ? List.of(policy.scopes) : List.of());
        }
        operation.response(401, OpenApiResponseContribution.of("Authentication required"));
        String roles = policy.roles.length == 0 ? null : "Requires role " + String.join(" or ", policy.roles)
                + (policy.on.length == 0 ? "" : " on " + String.join(", ", policy.on));
        String scopes = policy.scopes.length == 0 ? null : "Requires scopes " + String.join(" ", policy.scopes);
        if (roles != null || scopes != null) {
            operation.response(403, OpenApiResponseContribution.of(roles == null ? scopes : scopes == null ? roles : roles + "; " + scopes));
        }
        return operation.build();
    }
}
