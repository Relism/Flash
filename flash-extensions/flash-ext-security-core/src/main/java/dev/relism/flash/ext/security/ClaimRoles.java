package dev.relism.flash.ext.security;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Roles read from what the identity provider says about the caller, {@link Principal#claim}, instead
 * of the application's own data: a token claim, an assertion's attribute.
 *
 * <pre>{@code
 * new SecurityModule().roles(ClaimRoles.at("realm_access.roles"))   // Keycloak token
 * new SecurityModule().roles(ClaimRoles.at("groups"))
 * }</pre>
 */
public final class ClaimRoles implements RoleResolver {

    private final String name;
    private final String[] path;

    private ClaimRoles(String name) {
        this.name = name;
        this.path = name.split("\\.");
    }

    /**
     * Roles under {@code name}: the claim of that exact name, a URI such as
     * {@code http://schemas.microsoft.com/ws/2008/06/identity/claims/groups} included, else the
     * dot-separated path through nested claims.
     */
    public static ClaimRoles at(String name) {
        return new ClaimRoles(name);
    }

    /** The roles {@code principal}'s credential carries; none when it carries no list there. */
    @SuppressWarnings("unchecked")
    public List<String> of(Principal principal) {
        Object value = principal.claim(name);
        if (value == null && path.length > 1) {
            value = principal.claim(path[0]);
            for (int i = 1; i < path.length && value != null; i++) value = value instanceof Map<?, ?> map ? map.get(path[i]) : null;
        }
        return value instanceof List<?> roles ? (List<String>) roles : value instanceof Collection<?> roles ? List.copyOf((Collection<String>) roles) : List.of();
    }

    @Override
    public boolean hasRole(SecurityIdentity identity, String role, Target on) {
        return of(identity.principal()).contains(role);
    }
}
