package dev.relism.flash.ext.oidc;

import java.time.Instant;
import java.util.Map;

/**
 * An authenticated user's OIDC session — persisted in {@link OidcSessionStore} and
 * looked up via the {@code oidc_session} cookie on every request.
 *
 * <p>Sessions are immutable; a refreshed access token produces a new instance
 * that replaces the old one in the store (same {@link #id()}).
 */
public final class OidcSession {

    private final String              id;
    private final String              accessToken;
    private final String              idToken;
    private final String              refreshToken;        // may be null
    private final Instant             accessTokenExpiresAt;
    private final Map<String, Object> claims;              // decoded from id_token

    public OidcSession(String id, String accessToken, String idToken,
                       String refreshToken, Instant accessTokenExpiresAt,
                       Map<String, Object> claims) {
        this.id                   = id;
        this.accessToken          = accessToken;
        this.idToken              = idToken;
        this.refreshToken         = refreshToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.claims               = Map.copyOf(claims);
    }

    /**
     * Returns {@code true} if the access token has expired or will expire within
     * the next 30 seconds (eager refresh to avoid mid-request expiry).
     */
    public boolean isAccessTokenExpired() {
        return Instant.now().isAfter(accessTokenExpiresAt.minusSeconds(30));
    }

    public String              id()                   { return id; }
    public String              accessToken()          { return accessToken; }
    public String              idToken()              { return idToken; }
    public String              refreshToken()         { return refreshToken; }
    public Instant             accessTokenExpiresAt() { return accessTokenExpiresAt; }
    public Map<String, Object> claims()               { return claims; }
}
