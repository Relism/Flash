package dev.relism.ext.oidc;

import java.util.List;
import java.util.Map;

/**
 * Type-safe view over the JWT claims stored in {@link ClaimsHolder}.
 *
 * <p>Obtainable from any protected context via {@link ClaimsHolder#user()}.
 * Class-based handlers that extend the {@code SessionHandler} hierarchy already
 * have a provisioned DB user in {@code currentUser}; {@code OidcUser} complements
 * that by giving access to the raw OIDC claims when needed, and is the primary
 * API for lambda routes.
 *
 * <pre>{@code
 * // Lambda route (OidcMiddleware injected):
 * app.get("/api/whoami", (req, res) -> {
 *     OidcUser u = ClaimsHolder.user();
 *     return Map.of("sub", u.sub(), "email", u.email(), "roles", u.roles());
 * }, oidcMw.protect());
 *
 * // Class-based handler (currentUser is the DB entity; oidcUser() for raw claims):
 * protected Object handleAuthenticated(Request req, Response res) throws Exception {
 *     OidcUser u = oidcUser();           // same as ClaimsHolder.user()
 *     return json(res, currentUser);     // DB entity — provisioned from OIDC sub
 * }
 * }</pre>
 */
public final class OidcUser {

    private final Map<String, Object> claims;

    OidcUser(Map<String, Object> claims) {
        this.claims = claims;
    }

    // ── Common OIDC standard claims ───────────────────────────────────────────

    /** Subject identifier — unique, stable user ID issued by the provider. */
    public String sub() { return str("sub"); }

    /** User's email address ({@code email} claim). */
    public String email() { return str("email"); }

    /** Human-readable username ({@code preferred_username} claim). */
    public String username() { return str("preferred_username"); }

    /** Full display name ({@code name} claim). */
    public String name() { return str("name"); }

    // ── Roles ────────────────────────────────────────────────────────────────

    /**
     * Extracts the roles list by traversing a dot-separated claim path.
     *
     * <p>Example paths:
     * <ul>
     *   <li>{@code "realm_access.roles"} — Keycloak realm roles</li>
     *   <li>{@code "resource_access.my-client.roles"} — Keycloak client roles</li>
     *   <li>{@code "groups"} — Authelia / generic IdPs</li>
     * </ul>
     *
     * @return list of role strings, or an empty list if the path doesn't exist
     */
    @SuppressWarnings("unchecked")
    public List<String> roles(String claimPath) {
        String[] parts   = claimPath.split("\\.");
        Object   current = claims;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> m)) return List.of();
            current = m.get(part);
        }
        if (current instanceof List<?> list)
            return list.stream().map(Object::toString).toList();
        return List.of();
    }

    /** Returns {@code true} if the user holds {@code role} at the given claim path. */
    public boolean hasRole(String claimPath, String role) {
        return roles(claimPath).contains(role);
    }

    // ── Arbitrary claim access ─────────────────────────────────────────────

    /**
     * Returns the value of any claim, cast to {@code T}.
     *
     * @throws ClassCastException if the stored value is not assignable to {@code type}
     */
    public <T> T claim(String key, Class<T> type) {
        return type.cast(claims.get(key));
    }

    /** Returns the raw claim value, or {@code null} if absent. */
    public Object claim(String key) { return claims.get(key); }

    /** Escape hatch — returns the full unmodified claims map. */
    public Map<String, Object> claims() { return claims; }

    // ── Internals ─────────────────────────────────────────────────────────

    private String str(String key) {
        Object v = claims.get(key);
        return v != null ? v.toString() : null;
    }
}
