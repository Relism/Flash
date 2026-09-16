package dev.relism.flash.ext.security.oidc;

import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.RoleResolver;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.Target;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/** Roles read from the provider's token instead of the application's own data. */
public final class ClaimRoles implements RoleResolver {

    private final String[] path;

    private ClaimRoles(String claimPath) {
        this.path = claimPath.split("\\.");
    }

    /** Roles at a dot-separated claim path — {@code realm_access.roles} for Keycloak, {@code groups} for most others. */
    public static ClaimRoles at(String claimPath) {
        return new ClaimRoles(claimPath);
    }

    /** The roles {@code principal}'s token carries; none for a caller OIDC did not authenticate. */
    @SuppressWarnings("unchecked")
    public List<String> of(Principal principal) {
        Object value = principal instanceof OidcPrincipal oidc ? oidc.claims() : null;
        for (int i = 0; i < path.length && value != null; i++) value = value instanceof Map<?, ?> map ? map.get(path[i]) : null;
        return value instanceof List<?> roles ? (List<String>) roles : value instanceof Collection<?> roles ? List.copyOf((Collection<String>) roles) : List.of();
    }

    @Override
    public boolean hasRole(SecurityIdentity identity, String role, Target on) {
        return of(identity.principal()).contains(role);
    }
}
