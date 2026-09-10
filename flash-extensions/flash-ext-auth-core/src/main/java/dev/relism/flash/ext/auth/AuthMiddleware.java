package dev.relism.flash.ext.auth;

import dev.relism.flash.exceptions.HttpException;
import dev.relism.flash.extension.FlashContext;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.Response;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.routing.MiddlewareKey;
import dev.relism.flash.routing.MiddlewareNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns claims into a yes or a no. Exposed in the {@link FlashContext} for manual use on lambda
 * routes, and injected automatically for handlers annotated with {@link Authenticated},
 * {@link RolesAllowed} or {@link ScopesAllowed}.
 *
 * <p>It knows nothing about how the caller proved who they are — that is the
 * {@link CredentialSource} it is built with. What lives here is the half that is the same for
 * every mechanism: publish the claims for the request, match roles and scopes against them, clear
 * up afterwards.
 *
 * <pre>{@code
 * AuthMiddleware auth = app.ctx().require(AuthMiddleware.class);
 * app.get("/api/me", (req, res) -> ClaimsHolder.claim("sub"), auth.protect());
 * app.delete("/admin/users/{id}", handler, auth.requireRole("admin"));
 * }</pre>
 */
public class AuthMiddleware {

    /**
     * Boot-time identity of the node annotation-driven authorization mounts under. Public so an
     * extension that contributes its own middleware can order itself around authentication —
     * {@code MiddlewareNode.of(...).afterIfPresent(AuthMiddleware.POLICY)}.
     */
    public static final MiddlewareKey POLICY = MiddlewareKey.of("flash.auth.policy");

    private final AuthConfig       config;
    private final CredentialSource source;
    private final String[]         roleClaimPathParts;
    private final String[][]       scopeClaimPathParts;

    public AuthMiddleware(AuthConfig config, CredentialSource source) {
        this.config              = config;
        this.source              = source;
        this.roleClaimPathParts  = splitClaimPath(config.rolesClaimPath());
        this.scopeClaimPathParts = splitClaimPaths(config.scopeClaimPaths());
    }

    /**
     * Builds the middleware for {@code source}, publishes it in the context and registers the
     * annotation processor that mounts {@link Authenticated}, {@link RolesAllowed} and
     * {@link ScopesAllowed} on scanned handlers.
     *
     * <p>Every extension that contributes a {@link CredentialSource} calls this rather than
     * repeating the wiring — the processor and the {@link #POLICY} key belong to one place.
     */
    public static AuthMiddleware install(FlashContext ctx, AuthConfig config, CredentialSource source) {
        AuthMiddleware middleware = new AuthMiddleware(config, source);
        ctx.provide(AuthMiddleware.class, middleware);
        ctx.addAnnotationProcessor(handlerClass -> {
            AuthPolicy policy = AuthPolicy.compileFromAnnotations(handlerClass);
            return policy != null
                    ? List.of(MiddlewareNode.of(POLICY, middleware.authorize(policy)))
                    : List.of();
        });
        return middleware;
    }

    // -- Public API -----------------------------------------------------------

    /** The single configured claim path used by every transport for role checks. */
    public String rolesClaimPath() { return config.rolesClaimPath(); }

    /** The source this middleware authenticates with. */
    public CredentialSource source() { return source; }

    /**
     * The same authorization rules against a different credential source. Used where one route
     * needs a variant of an installed source — {@code flash-ext-mcp} protects {@code /mcp} with an
     * OIDC source whose challenges carry RFC 9728 resource metadata, while every other route keeps
     * the plain one.
     */
    public AuthMiddleware withSource(CredentialSource source) {
        return new AuthMiddleware(config, source);
    }

    /**
     * Rejects the request unless the caller is authenticated. How it is rejected — a 401 with a
     * challenge, a redirect into a sign-in flow — is the source's decision, not this one's.
     */
    public Middleware protect() {
        return next -> (req, res) -> {
            Map<String, Object> claims = source.authenticate(req, res);
            if (claims == null) return null;    // the source already answered the request
            ClaimsHolder.set(claims);
            try {
                return next.handle(req, res);
            } finally {
                ClaimsHolder.clear();
            }
        };
    }

    /**
     * Publishes claims when the caller happens to be authenticated and never rejects anyone. Use
     * it on public routes that personalise their response for signed-in callers.
     *
     * <pre>{@code
     * app.get("/", handler, auth.optional());
     * // Inside handler: ClaimsHolder.current() is non-null iff the caller is signed in.
     * }</pre>
     */
    public Middleware optional() {
        return next -> (req, res) -> {
            Map<String, Object> claims = source.peek(req);
            if (claims != null) ClaimsHolder.set(claims);
            try {
                return next.handle(req, res);
            } finally {
                ClaimsHolder.clear();
            }
        };
    }

    /**
     * Applies a policy compiled once at boot from a handler's annotations. This is the path
     * annotation-driven mounting takes.
     */
    public Middleware authorize(AuthPolicy policy) {
        if (policy.optionalAuth()) return optional();
        return next -> (req, res) -> {
            Map<String, Object> claims = source.authenticate(req, res);
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

    /** {@link #protect()} plus at least one of the given roles (OR semantics). */
    public Middleware requireRole(String... roles) {
        return authorize(AuthPolicy.rolesAny(roles));
    }

    /** {@link #protect()} plus every one of the given scopes. */
    public Middleware requireScopes(String... scopes) {
        return authorize(AuthPolicy.scopes(scopes, ScopesAllowed.Match.ALL));
    }

    /** {@link #protect()} plus at least one of the given scopes. */
    public Middleware requireAnyScope(String... scopes) {
        return authorize(AuthPolicy.scopes(scopes, ScopesAllowed.Match.ANY));
    }

    // -- Policy enforcement ---------------------------------------------------

    private void enforcePolicy(Map<String, Object> claims, AuthPolicy policy, Response res) {
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
        String challenge = source.insufficientScopeChallenge(required);
        if (challenge != null) res.header("WWW-Authenticate", challenge);
        throw HttpException.forbidden();
    }

    // -- Claim matching -------------------------------------------------------

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
            throw new IllegalStateException("Claim path cannot be blank");
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
            throw new IllegalStateException("Claim path cannot be blank");
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
}
