package dev.relism.ext.oidc;

import dev.relism.exceptions.HttpException;
import dev.relism.models.Request;
import dev.relism.routing.Middleware;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Request-level OIDC middleware. Exposed in the {@link dev.relism.extension.FlashContext}
 * for manual use on lambda routes; injected automatically for handlers annotated with
 * {@link Authenticated} or {@link RolesAllowed}.
 *
 * <p>Resolution order on each request:
 * <ol>
 *   <li>{@code Authorization: Bearer ...} header — validated via JWKS ({@link JwtValidator}).</li>
 *   <li>{@code oidc_session} cookie — looked up in {@link OidcSessionStore}; transparently
 *       refreshed if the access token is expired.</li>
 *   <li>Browser clients (no {@code Accept: application/json}) → redirect to
 *       {@code {routePrefix}/login?redirect={path}}.</li>
 *   <li>API clients → 401.</li>
 * </ol>
 *
 * <pre>{@code
 * // Manual use on a lambda route:
 * OidcMiddleware oidc = app.ctx().require(OidcMiddleware.class);
 * app.get("/api/me", (req, res) -> ClaimsHolder.claim("sub"), oidc.protect());
 * app.delete("/admin/users/{id}", handler, oidc.requireRole("admin"));
 * }</pre>
 */
public class OidcMiddleware {

    private final JwtValidator         validator;
    private final OidcConfig           config;
    private final OidcProviderMetadata meta;
    private final TokenClient          tokenClient;

    OidcMiddleware(JwtValidator validator, OidcConfig config,
                   OidcProviderMetadata meta, TokenClient tokenClient) {
        this.validator   = validator;
        this.config      = config;
        this.meta        = meta;
        this.tokenClient = tokenClient;
    }

    // -- Public API -----------------------------------------------------------

    /**
     * Validates the bearer token or session cookie. Browser clients are redirected
     * to the login page on failure; API clients receive 401.
     */
    public Middleware protect() {
        return next -> (req, res) -> {
            Map<String, Object> claims = resolve(req, res);
            if (claims == null) return null;    // redirect already written
            ClaimsHolder.set(claims);
            try {
                return next.handle(req, res);
            } finally {
                ClaimsHolder.clear();
            }
        };
    }

    /**
     * Silently populates {@link ClaimsHolder} if a valid bearer token or session cookie
     * is present, but never rejects or redirects unauthenticated requests. Use this on
     * public routes that want to personalise the response when the user happens to be
     * logged in (e.g. showing a username on a landing page).
     *
     * <pre>{@code
     * app.get("/", handler).with(oidc.optional());
     * // Inside handler: ClaimsHolder.user() is non-null iff the user is logged in.
     * }</pre>
     */
    public Middleware optional() {
        return next -> (req, res) -> {
            Map<String, Object> claims = resolveQuiet(req);
            if (claims != null) ClaimsHolder.set(claims);
            try {
                return next.handle(req, res);
            } finally {
                ClaimsHolder.clear();
            }
        };
    }

    /**
     * Like {@link #protect()} but also enforces that the caller holds at least one
     * of the given roles (OR semantics). Roles are extracted via
     * {@link OidcConfig#rolesClaimPath()}.
     */
    public Middleware requireRole(String... roles) {
        return next -> (req, res) -> {
            Map<String, Object> claims = resolve(req, res);
            if (claims == null) return null;
            checkRoles(claims, roles);
            ClaimsHolder.set(claims);
            try {
                return next.handle(req, res);
            } finally {
                ClaimsHolder.clear();
            }
        };
    }

    // -- Package-private: AnnotationProcessor hooks ---------------------------

    Middleware authenticatedMiddleware()          { return protect(); }
    Middleware optionalMiddleware()               { return optional(); }
    Middleware rolesMiddleware(String[] required) { return requireRole(required); }

    // -- Internals ------------------------------------------------------------

    /**
     * Like {@link #resolve} but never redirects or throws — returns {@code null} silently
     * when no valid credentials are present. Used by {@link #optional()}.
     */
    private Map<String, Object> resolveQuiet(Request req) {
        String auth = req.header("Authorization");
        if (auth != null && auth.startsWith("Bearer "))
            return validator.validate(auth.substring(7));

        String sessionId = cookieValue(req, "oidc_session");
        if (sessionId != null) {
            Optional<OidcSession> found = config.sessionStore().find(sessionId);
            if (found.isPresent()) {
                OidcSession session = found.get();
                if (!session.isAccessTokenExpired())
                    return session.claims();
                if (session.refreshToken() != null) {
                    try {
                        OidcSession refreshed = doRefresh(session);
                        config.sessionStore().save(refreshed);
                        return refreshed.claims();
                    } catch (Exception ignored) { }
                }
                config.sessionStore().delete(sessionId);
            }
        }
        return null;
    }

    /**
     * Returns claims on success, or {@code null} if a redirect was already written to
     * {@code res}. Throws {@link HttpException} 401/403 for API clients.
     */
    private Map<String, Object> resolve(Request req, dev.relism.models.Response res) {
        // 1. Bearer token
        String auth = req.header("Authorization");
        if (auth != null && auth.startsWith("Bearer "))
            return validator.validate(auth.substring(7));

        // 2. Session cookie
        String sessionId = cookieValue(req, "oidc_session");
        if (sessionId != null) {
            Optional<OidcSession> found = config.sessionStore().find(sessionId);
            if (found.isPresent()) {
                OidcSession session = found.get();

                if (!session.isAccessTokenExpired())
                    return session.claims();

                // Access token expired — try silent refresh
                if (session.refreshToken() != null) {
                    try {
                        OidcSession refreshed = doRefresh(session);
                        config.sessionStore().save(refreshed);
                        return refreshed.claims();
                    } catch (Exception ignored) {
                        // Refresh failed — fall through to re-authenticate
                    }
                }
                config.sessionStore().delete(sessionId);
            }
        }

        // 3. No valid credentials
        String accept = req.header("Accept");
        if (accept != null && accept.contains("application/json"))
            throw HttpException.unauthorized();

        // Browser — redirect to login, preserving the original URL in state
        String loginUrl = config.routePrefix() + "/login?redirect="
                + URLEncoder.encode(req.path(), StandardCharsets.UTF_8);
        res.redirect(loginUrl);
        return null;
    }

    private OidcSession doRefresh(OidcSession old) throws Exception {
        OidcTokenResponse tokens = tokenClient.refresh(
                meta.tokenEndpoint(), old.refreshToken());

        Map<String, Object> claims = mergeRefreshedClaims(tokens, old);

        return new OidcSession(
                old.id(),
                tokens.accessToken(),
                tokens.idToken() != null ? tokens.idToken() : old.idToken(),
                tokens.refreshToken() != null ? tokens.refreshToken() : old.refreshToken(),
                Instant.now().plusSeconds(tokens.expiresIn()),
                claims
        );
    }

    private void checkRoles(Map<String, Object> claims, String[] required) {
        List<String> actual = extractRoles(claims);
        for (String role : required) {
            if (actual.contains(role)) return;
        }
        throw HttpException.forbidden();
    }

    @SuppressWarnings("unchecked")
    private List<String> extractRoles(Map<String, Object> claims) {
        String[] parts   = config.rolesClaimPath().split("\\.");
        Object   current = claims;
        for (String part : parts) {
            if (!(current instanceof Map<?, ?> m)) return List.of();
            current = m.get(part);
        }
        if (current instanceof List<?> list)
            return list.stream().map(Object::toString).toList();
        return List.of();
    }

    private static Map<String, Object> mergeRefreshedClaims(OidcTokenResponse tokens, OidcSession old) {
        Map<String, Object> merged = new HashMap<>();
        // Fall back to old claims first, then overlay fresh token claims
        merged.putAll(old.claims());
        if (tokens.accessToken() != null)
            merged.putAll(JwtUtils.parseClaims(tokens.accessToken()));
        if (tokens.idToken() != null)
            merged.putAll(JwtUtils.parseClaims(tokens.idToken()));
        return Map.copyOf(merged);
    }

    // -- Shared cookie utility (also used by OidcExtension) -------------------

    static String cookieValue(Request req, String name) {
        String header = req.header("Cookie");
        if (header == null || header.isBlank()) return null;
        for (String part : header.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).strip().equals(name))
                return part.substring(eq + 1).strip();
        }
        return null;
    }
}
