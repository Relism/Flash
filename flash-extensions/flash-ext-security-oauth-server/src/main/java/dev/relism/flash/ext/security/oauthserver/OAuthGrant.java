package dev.relism.flash.ext.security.oauthserver;

import java.time.Instant;
import java.util.Map;

/**
 * An authorization code or a refresh token, stored under the hash of its value. Every grant descending
 * from one authorization shares a {@code family}, so a replayed code or a reused refresh token revokes
 * the whole of it (OAuth 2.1 §4.1.2, §4.3.1).
 *
 * @param subject     who authorized it, as {@link OAuthSubject#id()}
 * @param claims      carried into every access token issued from it
 * @param redirectUri the one the authorization request named, which the code exchange must repeat; {@code null} otherwise
 * @param challenge   the PKCE {@code S256} challenge; codes only
 * @param usedAt      when it was exchanged, {@code null} while it is unused
 */
public record OAuthGrant(String hash, Kind kind, String family, String clientId, String subject, Map<String, Object> claims,
                         String scope, String resource, String redirectUri, String challenge, Instant expiresAt, Instant usedAt) {

    public enum Kind { CODE, REFRESH }

    public OAuthGrant {
        claims = Map.copyOf(claims);
    }

    public OAuthGrant used(Instant at) {
        return new OAuthGrant(hash, kind, family, clientId, subject, claims, scope, resource, redirectUri, challenge, expiresAt, at);
    }
}
