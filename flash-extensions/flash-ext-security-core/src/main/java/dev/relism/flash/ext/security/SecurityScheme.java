package dev.relism.flash.ext.security;

import java.util.Map;

/**
 * A credential as OpenAPI names and defines it, the {@code WWW-Authenticate} challenge an anonymous
 * API call receives for it, and — for OAuth — the issuer that grants it.
 *
 * @param definition the OpenAPI Security Scheme Object, verbatim
 * @param issuer     the authorization server's issuer identifier — {@code "/"} when the application is its own,
 *                   at whatever {@link Security#origin(dev.relism.flash.http.Request)} it is reached by;
 *                   {@code null} for anything else
 */
public record SecurityScheme(String name, Map<String, Object> definition, String challenge, String issuer) {

    public static SecurityScheme bearer(String name, String bearerFormat) {
        return new SecurityScheme(name, Map.of("type", "http", "scheme", "bearer", "bearerFormat", bearerFormat),
                "Bearer realm=\"" + name + "\"", null);
    }

    /** HTTP Basic: user name and password in every request, UTF-8 as the challenge says (RFC 7617). */
    public static SecurityScheme basic(String realm) {
        return new SecurityScheme("basic", Map.of("type", "http", "scheme", "basic"),
                "Basic realm=\"" + realm + "\", charset=\"UTF-8\"", null);
    }

    public static SecurityScheme openIdConnect(String name, String issuer) {
        return new SecurityScheme(name,
                Map.of("type", "openIdConnect", "openIdConnectUrl", issuer + (issuer.endsWith("/") ? "" : "/") + ".well-known/openid-configuration"),
                "Bearer realm=\"" + name + "\"", issuer);
    }
}
