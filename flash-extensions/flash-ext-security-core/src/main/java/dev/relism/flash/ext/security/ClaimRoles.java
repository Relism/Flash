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

    /** The roles {@code principal}'s credential carries; none when it carries no collection of strings there. */
    @SuppressWarnings("unchecked")
    public List<String> of(Principal principal) {
        Collection<?> roles = roles(principal);
        return roles instanceof List<?> list ? (List<String>) list : List.copyOf((Collection<String>) roles);
    }

    @Override
    public boolean hasRole(SecurityIdentity identity, String role, Target on) {
        return roles(identity.principal()).contains(role);
    }

    /** The collection under the name, when every element is a string; otherwise none, and nothing is copied. */
    private Collection<?> roles(Principal principal) {
        Object value = principal.claim(name);
        if (value == null && path.length > 1) {
            value = principal.claim(path[0]);
            for (int i = 1; i < path.length && value != null; i++) value = value instanceof Map<?, ?> map ? map.get(path[i]) : null;
        }
        if (!(value instanceof Collection<?> roles)) return List.of();
        for (Object role : roles) if (!(role instanceof String)) return List.of();
        return roles;
    }
}
