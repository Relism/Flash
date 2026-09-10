package dev.relism.flash.ext.auth;

import java.time.Instant;
import java.util.Map;

/**
 * A signed-in caller's server-side session — saved in a {@link SessionStore} and looked up by a
 * cookie on every request.
 *
 * <p>Immutable: renewing one produces a new instance that replaces the old under the same
 * {@link #id()}.
 *
 * <p>{@link #attributes()} is whatever the {@link CredentialSource} needs to keep alongside the
 * claims and nothing this module interprets — OpenID Connect stores its access, id and refresh
 * tokens there so that renewal is its business rather than core's.
 */
public final class Session {

    /** Renew this far before the real expiry, so a session cannot lapse mid-request. */
    private static final long EAGER_RENEWAL_SECONDS = 30;

    private final String              id;
    private final Map<String, Object> claims;
    private final Instant             expiresAt;
    private final Map<String, Object> attributes;

    public Session(String id, Map<String, Object> claims, Instant expiresAt,
                   Map<String, Object> attributes) {
        this.id         = id;
        this.claims     = Map.copyOf(claims);
        this.expiresAt  = expiresAt;
        this.attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** True once the session is within {@value #EAGER_RENEWAL_SECONDS} seconds of expiring. */
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt.minusSeconds(EAGER_RENEWAL_SECONDS));
    }

    /** One attribute, or {@code null} when the source never stored it. */
    public Object attribute(String key) { return attributes.get(key); }

    /** One attribute as a String, or {@code null}. */
    public String attributeAsString(String key) {
        Object v = attributes.get(key);
        return v != null ? v.toString() : null;
    }

    public String              id()         { return id; }
    public Map<String, Object> claims()     { return claims; }
    public Instant             expiresAt()  { return expiresAt; }
    public Map<String, Object> attributes() { return attributes; }
}
