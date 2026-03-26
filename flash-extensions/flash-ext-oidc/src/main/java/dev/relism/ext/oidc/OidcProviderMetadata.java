package dev.relism.ext.oidc;

/**
 * OIDC provider endpoints discovered from {@code {issuer}/.well-known/openid-configuration}.
 *
 * <p>{@link #endSessionEndpoint()} may be {@code null} — not all providers expose it
 * (e.g. some Authelia configurations omit it).
 */
public record OidcProviderMetadata(
        String authorizationEndpoint,
        String tokenEndpoint,
        String userinfoEndpoint,
        String jwksUri,
        String endSessionEndpoint       // nullable
) {}
