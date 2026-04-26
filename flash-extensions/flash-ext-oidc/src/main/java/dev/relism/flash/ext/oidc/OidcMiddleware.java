package dev.relism.flash.ext.oidc;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.models.Response;
import dev.relism.flash.models.Request;
import dev.relism.flash.routing.Middleware;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Request-level OIDC middleware. Exposed in the {@link FlashContext}
 * for manual use on lambda routes; injected automatically for handlers annotated with
 * {@link Authenticated}, {@link RolesAllowed} or {@link ScopesAllowed}.
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

    private static final String BEARER = "Bearer";

    private final JwtValidator         validator;
    private final OidcConfig           config;
    private final OidcProviderMetadata meta;
    private final TokenClient          tokenClient;
    private final String[]             roleClaimPathParts;
    private final String[][]           scopeClaimPathParts;

    OidcMiddleware(JwtValidator validator, OidcConfig config,
                   OidcProviderMetadata meta, TokenClient tokenClient) {
        this.validator   = validator;
        this.config      = config;
        this.meta        = meta;
        this.tokenClient = tokenClient;
        this.roleClaimPathParts = splitClaimPath(config.rolesClaimPath());
        this.scopeClaimPathParts = splitClaimPaths(config.scopeClaimPaths());
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
     * app.get("/", handler, oidc.optional());
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
     * Compiled authorization policy path used by annotation-driven mounting.
     * The policy is immutable and built once at boot.
     */
    public Middleware authorize(OidcAuthPolicy policy) {
        if (policy.optionalAuth()) return optional();
        return next -> (req, res) -> {
            Map<String, Object> claims = resolve(req, res);
            if (claims == null) return null;
            enforcePolicy(claims, policy, res);
            ClaimsHolder.set(claims);
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
        return authorize(OidcAuthPolicy.rolesAny(roles));
    }

    /**
     * Requires all listed scopes to be present in the token.
     * Scopes are resolved from configured claim paths (default: {@code scope,scp}).
     */
    public Middleware requireScopes(String... scopes) {
        return authorize(OidcAuthPolicy.scopes(scopes, ScopesAllowed.Match.ALL));
    }

    /**
     * Requires at least one of the listed scopes to be present in the token.
     * Scopes are resolved from configured claim paths (default: {@code scope,scp}).
     */
    public Middleware requireAnyScope(String... scopes) {
        return authorize(OidcAuthPolicy.scopes(scopes, ScopesAllowed.Match.ANY));
    }

    // -- Package-private: AnnotationProcessor hooks ---------------------------

    Middleware authenticatedMiddleware()          { return protect(); }
    Middleware optionalMiddleware()               { return optional(); }
    Middleware rolesMiddleware(String[] required) { return requireRole(required); }
    Middleware scopesMiddleware(String[] required, ScopesAllowed.Match match) {
        return authorize(OidcAuthPolicy.scopes(required, match));
    }
    Middleware policyMiddleware(OidcAuthPolicy policy) { return authorize(policy); }

    // -- Internals ------------------------------------------------------------

    /**
     * Like {@link #resolve} but never redirects or throws — returns {@code null} silently
     * when no valid credentials are present. Used by {@link #optional()}.
     */
    private Map<String, Object> resolveQuiet(Request req) {
        String bearerToken = extractBearerToken(req.header("Authorization"));
        if (bearerToken != null) {
            try {
                return validator.validate(bearerToken);
            } catch (Exception ignored) {
                return null;
            }
        }

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
    private Map<String, Object> resolve(Request req, Response res) {
        // 1. Bearer token
        String bearerToken = extractBearerToken(req.header("Authorization"));
        if (bearerToken != null) {
            try {
                return validator.validate(bearerToken);
            } catch (HttpException e) {
                res.header("WWW-Authenticate", invalidTokenChallenge());
                throw e;
            }
        }

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
        if (accept != null && accept.contains("application/json")) {
            res.header("WWW-Authenticate", bearerChallenge());
            throw HttpException.unauthorized();
        }

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

    private void enforcePolicy(Map<String, Object> claims, OidcAuthPolicy policy, Response res) {
        checkRoles(claims, policy.requiredRoles());
        checkScopes(claims, policy.requiredScopes(), policy.scopeMatch(), res);
    }

    private void checkRoles(Map<String, Object> claims, String[] required) {
        if (required.length == 0) return;
        if (rolesAllowed(claims, required)) return;
        throw HttpException.forbidden();
    }

    private void checkScopes(Map<String, Object> claims, String[] required, ScopesAllowed.Match match,
                             Response res) {
        if (required.length == 0) return;
        if (scopesAllowed(claims, required, match)) return;
        res.header("WWW-Authenticate", insufficientScopeChallenge(required));
        throw HttpException.forbidden();
    }

    static String extractBearerToken(String authorizationHeader) {
        if (authorizationHeader == null) return null;
        int len = authorizationHeader.length();
        int start = 0;
        while (start < len && Character.isWhitespace(authorizationHeader.charAt(start))) start++;
        int schemeEnd = start + BEARER.length();
        if (schemeEnd > len || !authorizationHeader.regionMatches(true, start, BEARER, 0, BEARER.length())) {
            return null;
        }
        if (schemeEnd == len || !Character.isWhitespace(authorizationHeader.charAt(schemeEnd))) {
            return null;
        }
        int tokenStart = schemeEnd;
        while (tokenStart < len && Character.isWhitespace(authorizationHeader.charAt(tokenStart))) tokenStart++;
        if (tokenStart >= len) return null;
        int tokenEnd = len;
        while (tokenEnd > tokenStart && Character.isWhitespace(authorizationHeader.charAt(tokenEnd - 1))) tokenEnd--;
        return tokenEnd > tokenStart ? authorizationHeader.substring(tokenStart, tokenEnd) : null;
    }

    String bearerChallenge() {
        return BEARER + " realm=\"" + quoted(config.schemeName()) + "\"";
    }

    String invalidTokenChallenge() {
        return bearerChallenge() + ", error=\"invalid_token\"";
    }

    String insufficientScopeChallenge(String[] requiredScopes) {
        return bearerChallenge() + ", error=\"insufficient_scope\", scope=\""
                + quoted(spaceDelimited(requiredScopes)) + "\"";
    }

    private static String spaceDelimited(String[] values) {
        if (values == null || values.length == 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(' ');
            sb.append(values[i]);
        }
        return sb.toString();
    }

    private static String quoted(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '"' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    boolean rolesAllowed(Map<String, Object> claims, String[] required) {
        Object actual = valueAtPath(claims, roleClaimPathParts);
        if (actual == null) return false;
        for (String role : required) {
            if (containsToken(actual, role)) return true;
        }
        return false;
    }

    boolean scopesAllowed(Map<String, Object> claims, String[] required, ScopesAllowed.Match match) {
        if (match == ScopesAllowed.Match.ALL) {
            for (String scope : required) {
                if (!hasScope(claims, scope)) return false;
            }
            return true;
        }
        for (String scope : required) {
            if (hasScope(claims, scope)) return true;
        }
        return false;
    }

    private boolean hasScope(Map<String, Object> claims, String scope) {
        for (String[] pathParts : scopeClaimPathParts) {
            Object value = valueAtPath(claims, pathParts);
            if (value != null && containsToken(value, scope)) return true;
        }
        return false;
    }

    private static Object valueAtPath(Map<String, Object> claims, String[] pathParts) {
        Object current = claims;
        for (String part : pathParts) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
            if (current == null) return null;
        }
        return current;
    }

    private static boolean containsToken(Object source, String token) {
        if (source instanceof String s) return containsDelimitedToken(s, token);
        if (source instanceof List<?> list) {
            for (Object item : list) {
                if (item == null) continue;
                if (tokenEquals(item.toString(), token)) return true;
            }
            return false;
        }
        if (source instanceof Object[] arr) {
            for (Object item : arr) {
                if (item == null) continue;
                if (tokenEquals(item.toString(), token)) return true;
            }
            return false;
        }
        return tokenEquals(source.toString(), token);
    }

    private static boolean containsDelimitedToken(String value, String token) {
        int len = value.length();
        int i = 0;
        while (i < len) {
            while (i < len && isScopeDelimiter(value.charAt(i))) i++;
            int start = i;
            while (i < len && !isScopeDelimiter(value.charAt(i))) i++;
            int end = i;
            if (end > start && end - start == token.length() && value.regionMatches(start, token, 0, token.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean tokenEquals(String value, String token) {
        int start = 0;
        int end = value.length();
        while (start < end && Character.isWhitespace(value.charAt(start))) start++;
        while (end > start && Character.isWhitespace(value.charAt(end - 1))) end--;
        return end - start == token.length() && value.regionMatches(start, token, 0, token.length());
    }

    private static boolean isScopeDelimiter(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == ',';
    }

    private static String[] splitClaimPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalStateException("OIDC claim path cannot be blank");
        }
        List<String> parts = new ArrayList<>(4);
        int start = 0;
        int len = path.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || path.charAt(i) == '.') {
                String p = path.substring(start, i).trim();
                if (!p.isEmpty()) parts.add(p);
                start = i + 1;
            }
        }
        if (parts.isEmpty()) {
            throw new IllegalStateException("OIDC claim path cannot be blank");
        }
        return parts.toArray(String[]::new);
    }

    private static String[][] splitClaimPaths(String paths) {
        String source = (paths == null || paths.isBlank()) ? "scope,scp" : paths;
        List<String[]> out = new ArrayList<>(4);
        int start = 0;
        int len = source.length();
        for (int i = 0; i <= len; i++) {
            if (i == len || source.charAt(i) == ',') {
                String raw = source.substring(start, i).trim();
                if (!raw.isEmpty()) out.add(splitClaimPath(raw));
                start = i + 1;
            }
        }
        if (out.isEmpty()) {
            return new String[][]{ splitClaimPath("scope"), splitClaimPath("scp") };
        }
        return out.toArray(String[][]::new);
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
        int len   = header.length();
        int start = 0;
        while (start < len) {
            int semi = header.indexOf(';', start);
            int end  = semi < 0 ? len : semi;
            int eq   = header.indexOf('=', start);
            if (eq > start && eq < end) {
                int ns = start, ne = eq;
                while (ns < ne && header.charAt(ns)   == ' ') ns++;
                while (ne > ns && header.charAt(ne-1) == ' ') ne--;
                if (ne - ns == name.length() && header.regionMatches(ns, name, 0, name.length()))
                    return header.substring(eq + 1, end).strip();
            }
            start = end + 1;
        }
        return null;
    }
}
