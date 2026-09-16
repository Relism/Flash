package dev.relism.flash.ext.security.oidc;

/**
 * An OpenID Provider the application trusts, and the client the application is registered as there.
 *
 * @param id     names the provider in routes ({@code /auth/oidc/{id}/login}), OpenAPI and principals
 * @param name   what a sign-in page shows
 * @param issuer exactly as the provider publishes it — some end in {@code /}
 * @param scopes requested at sign-in, space-delimited
 */
public record OidcProvider(String id, String name, String issuer, String clientId, String clientSecret, String scopes) {

    public OidcProvider {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("OIDC provider id must be URL-safe: " + id);
    }

    public static OidcProvider of(String id, String issuer, String clientId, String clientSecret) {
        return new OidcProvider(id, id, issuer, clientId, clientSecret, "openid profile email");
    }

    public OidcProvider name(String name) {
        return new OidcProvider(id, name, issuer, clientId, clientSecret, scopes);
    }

    public OidcProvider scopes(String scopes) {
        return new OidcProvider(id, name, issuer, clientId, clientSecret, scopes);
    }

    String discoveryUrl() {
        return issuer + (issuer.endsWith("/") ? "" : "/") + ".well-known/openid-configuration";
    }
}
