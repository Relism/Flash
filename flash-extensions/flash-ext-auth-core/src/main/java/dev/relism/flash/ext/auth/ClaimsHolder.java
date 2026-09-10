package dev.relism.flash.ext.auth;

import java.util.Map;

/**
 * The current request's claims, published by {@link AuthMiddleware} before the handler runs and
 * cleared in a {@code finally} afterwards.
 *
 * <p>Safe with virtual threads: each request gets its own, so a {@link ThreadLocal} is naturally
 * isolated per request.
 *
 * <p>Writing is deliberately not public. A {@link CredentialSource} returns claims and the
 * middleware publishes them, so no code outside this module can put claims on a request that did
 * not carry them.
 *
 * <pre>{@code
 * // Inside any handler behind @Authenticated or @RolesAllowed:
 * Claims caller = ClaimsHolder.current();
 * String email  = caller.email();
 * List<String> roles = caller.roles("realm_access.roles");
 *
 * // Raw escape hatch:
 * Map<String, Object> all = ClaimsHolder.map();
 * }</pre>
 */
public final class ClaimsHolder {

    private static final ThreadLocal<Map<String, Object>> HOLDER = new ThreadLocal<>();

    private ClaimsHolder() {}

    /** Called by {@link AuthMiddleware} once a source has authenticated the request. */
    static void set(Map<String, Object> claims) {
        HOLDER.set(claims);
    }

    /** Called by {@link AuthMiddleware} in the {@code finally} block. */
    static void clear() {
        HOLDER.remove();
    }

    /**
     * A typed view of the current request's claims, or {@code null} when the route carries no
     * authentication middleware or the caller is anonymous under
     * {@link Authenticated}{@code (optional = true)}.
     */
    public static Claims current() {
        Map<String, Object> claims = HOLDER.get();
        return claims != null ? new Claims(claims) : null;
    }

    /** The raw claims map for the current request, or {@code null}. @see #current() */
    public static Map<String, Object> map() {
        return HOLDER.get();
    }

    /** A single claim as a String, or {@code null} when absent or the caller is anonymous. */
    public static String claim(String key) {
        Map<String, Object> claims = HOLDER.get();
        if (claims == null) return null;
        Object v = claims.get(key);
        return v != null ? v.toString() : null;
    }
}
