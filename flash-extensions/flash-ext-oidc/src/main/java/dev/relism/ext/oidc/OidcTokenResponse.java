package dev.relism.ext.oidc;

/** Parsed response from an OAuth2 token endpoint. Package-private — internal use only. */
record OidcTokenResponse(
        String accessToken,
        String idToken,         // may be null on refresh if provider omits it
        String refreshToken,    // may be null
        int    expiresIn,
        int    refreshExpiresIn
) {}
