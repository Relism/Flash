package dev.relism.flash.ext.security.apikey;

import java.time.Instant;

/**
 * An API key as the application stores it: never the secret, only its hash, and the grant it was
 * issued with — whatever the application authorizes on.
 *
 * @param expiresAt {@code null} for a key that does not expire
 * @param revokedAt {@code null} for a key that has not been revoked
 */
public record ApiKey<G>(String id, String secretHash, G grant, Instant expiresAt, Instant revokedAt) {

    boolean isActive() {
        return revokedAt == null && (expiresAt == null || expiresAt.toEpochMilli() > System.currentTimeMillis());
    }
}
