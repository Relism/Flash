package dev.relism.flash.ext.security;

/** What a handler's or tool's security annotations require, compiled once at boot. Checks allocate nothing. */
public final class SecurityPolicy {

    /** Any authenticated caller. */
    public static final SecurityPolicy AUTHENTICATED = new SecurityPolicy(true, new String[0], new String[0], new String[0]);

    final boolean required;
    final String[] roles;
    final String[] on;
    final String[] scopes;
    final String scopeChallenge;

    private SecurityPolicy(boolean required, String[] roles, String[] on, String[] scopes) {
        this.required = required;
        this.roles = roles;
        this.on = on;
        this.scopes = scopes;
        this.scopeChallenge = "Bearer error=\"insufficient_scope\", scope=\"" + String.join(" ", scopes) + "\"";
    }

    /** A caller holding any of {@code roles}: what {@code @RolesAllowed} says, for a route without a class. */
    public static SecurityPolicy roles(String... roles) {
        if (roles.length == 0) throw new IllegalArgumentException("roles names nothing");
        return new SecurityPolicy(true, roles.clone(), AUTHENTICATED.on, AUTHENTICATED.scopes);
    }

    /** The policy {@code type} declares, or {@code null} when it carries no security annotation. */
    public static SecurityPolicy of(Class<?> type) {
        boolean permitAll = type.isAnnotationPresent(PermitAll.class);
        boolean authenticated = type.isAnnotationPresent(Authenticated.class);
        RolesAllowed roles = type.getAnnotation(RolesAllowed.class);
        ScopesAllowed scopes = type.getAnnotation(ScopesAllowed.class);
        if (!permitAll && !authenticated && roles == null && scopes == null) return null;
        if (permitAll && (authenticated || roles != null || scopes != null)) {
            throw new IllegalStateException("@PermitAll contradicts the other security annotations on " + type.getName());
        }
        return new SecurityPolicy(!permitAll,
                roles == null ? AUTHENTICATED.roles : values(roles.value(), "@RolesAllowed", type),
                roles == null ? AUTHENTICATED.on : roles.on(),
                scopes == null ? AUTHENTICATED.scopes : values(scopes.value(), "@ScopesAllowed", type));
    }

    /** False only for {@link PermitAll}. */
    public boolean required() {
        return required;
    }

    public boolean requiresRoles() {
        return roles.length > 0;
    }

    public boolean permitsScopes(SecurityIdentity identity) {
        for (String scope : scopes) if (!identity.hasScope(scope)) return false;
        return true;
    }

    public boolean permitsRoles(SecurityIdentity identity, Target target) {
        if (roles.length == 0) return true;
        for (String role : roles) if (identity.hasRole(role, target)) return true;
        return false;
    }

    private static String[] values(String[] values, String annotation, Class<?> type) {
        if (values.length == 0) throw new IllegalStateException(annotation + " on " + type.getName() + " names nothing");
        return values;
    }
}
