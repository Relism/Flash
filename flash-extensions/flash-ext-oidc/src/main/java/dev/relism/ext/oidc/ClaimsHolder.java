package dev.relism.ext.oidc;

import java.util.Map;

/**
 * Thread-local store for JWT claims, populated by the OIDC middleware before
 * the handler runs and cleared in the {@code finally} block afterward.
 *
 * <p>Safe with virtual threads: each request gets its own virtual thread, so
 * {@link ThreadLocal} values are naturally isolated per request.
 *
 * <pre>{@code
 * // Inside any handler protected by @Authenticated or @RolesAllowed:
 *
 * // Preferred — typed wrapper:
 * OidcUser user = ClaimsHolder.user();
 * String email  = user.email();
 * List<String> roles = user.roles("realm_access.roles");
 *
 * // Raw escape hatch:
 * Map<String, Object> all = ClaimsHolder.get();
 * }</pre>
 */
public final class ClaimsHolder {

    private static final ThreadLocal<Map<String, Object>> HOLDER = new ThreadLocal<>();

    private ClaimsHolder() {}

    /** Called by the OIDC middleware after successful token validation. */
    static void set(Map<String, Object> claims) {
        HOLDER.set(claims);
    }

    /** Called by the OIDC middleware in the {@code finally} block. */
    static void clear() {
        HOLDER.remove();
    }

    /**
     * Returns a type-safe {@link OidcUser} view of the current request's claims,
     * or {@code null} if the route is not protected by OIDC middleware.
     *
     * <p>This is the preferred entry point for both lambda and class-based handlers.
     */
    public static OidcUser user() {
        Map<String, Object> claims = HOLDER.get();
        return claims != null ? new OidcUser(claims) : null;
    }

    /**
     * Returns the raw claims map for the current request, or {@code null} if
     * the route is not protected by OIDC middleware.
     *
     * @see #user() for the preferred type-safe accessor
     */
    public static Map<String, Object> get() {
        return HOLDER.get();
    }

    /**
     * Returns the value of a single claim as a String, or {@code null} if
     * the claim is absent or the request is not authenticated.
     */
    public static String claim(String key) {
        Map<String, Object> claims = HOLDER.get();
        if (claims == null) return null;
        Object v = claims.get(key);
        return v != null ? v.toString() : null;
    }
}
