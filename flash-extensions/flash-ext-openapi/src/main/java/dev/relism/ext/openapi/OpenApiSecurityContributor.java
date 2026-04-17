package dev.relism.ext.openapi;

import java.util.List;
import java.util.Map;

/**
 * Pluggable security scheme contributor for the OpenAPI spec.
 *
 * <p>Extensions that enforce authentication (e.g. {@code flash-ext-oidc}) implement
 * this interface and register an instance into {@link OpenApiSecurityRegistry} via the
 * {@link dev.relism.extension.FlashContext}. {@link OpenApiExtension} picks it up
 * at spec-generation time — no coupling between the two extensions at install time.
 *
 * <p>Multi-tenant: multiple contributors may coexist. For handlers secured by
 * {@code @Authenticated}/{@code @RolesAllowed}, each matching contributor adds its
 * own entry to the operation's {@code security} array (OpenAPI OR semantics).
 */
public interface OpenApiSecurityContributor {

    /**
     * Unique scheme name used as a key in {@code components.securitySchemes}
     * and referenced from each operation's {@code security} array.
     */
    String schemeName();

    /**
     * The OpenAPI security scheme definition object placed under
     * {@code components.securitySchemes.<schemeName>}.
     *
     * <p>Example for OIDC:
     * <pre>{@code
     * Map.of("type", "openIdConnect",
     *        "openIdConnectUrl", "https://idp.example.com/.well-known/openid-configuration")
     * }</pre>
     */
    Map<String, Object> schemeDefinition();

    /**
     * Returns the scopes/roles required for the given handler class under this scheme,
     * or {@code null} if this contributor does not secure the handler.
     *
     * <ul>
     *   <li>{@code null}       — handler is not secured by this contributor (skip)</li>
     *   <li>empty list         — handler requires authentication, no specific scopes</li>
     *   <li>non-empty list     — handler requires these scopes/roles</li>
     * </ul>
     */
    List<String> requiredFor(Class<?> handlerClass);

    /**
     * Optional auto-injected operation responses for handlers secured by this contributor.
     * Key = HTTP status code, value = description.
     */
    default Map<Integer, String> autoResponsesFor(Class<?> handlerClass) {
        return Map.of();
    }
}
