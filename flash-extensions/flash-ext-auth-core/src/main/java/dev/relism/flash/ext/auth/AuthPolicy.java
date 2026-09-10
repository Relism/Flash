package dev.relism.flash.ext.auth;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * Compiled authorization policy derived from handler annotations at mount time.
 * Immutable and allocation-free on the request hot path.
 */
public final class AuthPolicy {

    private static final String[] EMPTY = new String[0];

    private static final AuthPolicy AUTH_REQUIRED = new AuthPolicy(
            false, EMPTY, EMPTY, ScopesAllowed.Match.ALL);
    private static final AuthPolicy AUTH_OPTIONAL = new AuthPolicy(
            true, EMPTY, EMPTY, ScopesAllowed.Match.ALL);

    private final boolean optionalAuth;
    private final String[] requiredRoles;
    private final String[] requiredScopes;
    private final ScopesAllowed.Match scopeMatch;

    private AuthPolicy(boolean optionalAuth,
                           String[] requiredRoles,
                           String[] requiredScopes,
                           ScopesAllowed.Match scopeMatch) {
        this.optionalAuth   = optionalAuth;
        this.requiredRoles  = requiredRoles;
        this.requiredScopes = requiredScopes;
        this.scopeMatch     = scopeMatch;
    }

    public static AuthPolicy authenticated() { return AUTH_REQUIRED; }

    public static AuthPolicy optional() { return AUTH_OPTIONAL; }

    public static AuthPolicy rolesAny(String... roles) {
        return new AuthPolicy(false, normalizeRequired("RolesAllowed", roles), EMPTY, ScopesAllowed.Match.ALL);
    }

    public static AuthPolicy scopes(String[] scopes, ScopesAllowed.Match match) {
        return new AuthPolicy(false, EMPTY, normalizeRequired("ScopesAllowed", scopes), match);
    }

    public static AuthPolicy compileFromAnnotations(Class<?> handlerClass) {
        Authenticated auth = handlerClass.getAnnotation(Authenticated.class);
        RolesAllowed roles = handlerClass.getAnnotation(RolesAllowed.class);
        ScopesAllowed scopes = handlerClass.getAnnotation(ScopesAllowed.class);

        if (auth == null && roles == null && scopes == null) return null;

        boolean optionalAuth = auth != null && auth.optional();
        String[] requiredRoles = roles != null ? normalizeRequired("RolesAllowed", roles.value()) : EMPTY;
        String[] requiredScopes = scopes != null ? normalizeRequired("ScopesAllowed", scopes.value()) : EMPTY;
        ScopesAllowed.Match scopeMatch = scopes != null ? scopes.match() : ScopesAllowed.Match.ALL;

        if (optionalAuth && (requiredRoles.length > 0 || requiredScopes.length > 0)) {
            throw new IllegalStateException("@Authenticated(optional = true) cannot be combined with @RolesAllowed/@ScopesAllowed on "
                    + handlerClass.getName());
        }

        return new AuthPolicy(optionalAuth, requiredRoles, requiredScopes, scopeMatch);
    }

    public static List<String> openApiScopesFor(Class<?> handlerClass) {
        Authenticated auth = handlerClass.getAnnotation(Authenticated.class);
        RolesAllowed roles = handlerClass.getAnnotation(RolesAllowed.class);
        ScopesAllowed scopes = handlerClass.getAnnotation(ScopesAllowed.class);
        if (auth == null && roles == null && scopes == null) return null;
        if (scopes == null) return List.of();
        return List.of(normalizeRequired("ScopesAllowed", scopes.value()));
    }

    public boolean optionalAuth() { return optionalAuth; }

    public String[] requiredRoles() { return requiredRoles; }

    public String[] requiredScopes() { return requiredScopes; }

    public ScopesAllowed.Match scopeMatch() { return scopeMatch; }

    private static String[] normalizeRequired(String annotation, String[] values) {
        if (values == null || values.length == 0)
            throw new IllegalStateException("@" + annotation + " requires at least one value");

        LinkedHashSet<String> normalized = new LinkedHashSet<>(values.length);
        for (String raw : values) {
            if (raw == null) continue;
            String trimmed = raw.trim();
            if (!trimmed.isEmpty()) normalized.add(trimmed);
        }
        if (normalized.isEmpty())
            throw new IllegalStateException("@" + annotation + " requires at least one non-empty value");

        return normalized.toArray(String[]::new);
    }
}
