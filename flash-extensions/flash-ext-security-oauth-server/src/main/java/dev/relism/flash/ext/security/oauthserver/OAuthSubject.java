package dev.relism.flash.ext.security.oauthserver;

import java.util.Map;

/**
 * Whom an authorization is for, as the application names them: {@code id} becomes the access token's
 * {@code sub}, {@code claims} travel in every token issued from it. Standard claims win over these.
 */
public record OAuthSubject(String id, Map<String, Object> claims) {

    public OAuthSubject {
        claims = Map.copyOf(claims);
    }
}
