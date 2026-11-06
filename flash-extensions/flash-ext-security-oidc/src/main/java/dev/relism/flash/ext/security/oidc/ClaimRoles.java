package dev.relism.flash.ext.security.oidc;

import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.RoleResolver;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.Target;

import java.util.List;

/**
 * Moved to {@code flash-ext-security-core}, where it reads any principal's claims. This one still reads
 * only an {@link OidcPrincipal}'s, as it always did: an application that adds another way to sign in does
 * not start taking roles from it unawares.
 *
 * @deprecated use {@link dev.relism.flash.ext.security.ClaimRoles}
 */
@Deprecated(since = "4.1.0", forRemoval = true)
public final class ClaimRoles implements RoleResolver {

    private final dev.relism.flash.ext.security.ClaimRoles roles;

    private ClaimRoles(String claimPath) {
        this.roles = dev.relism.flash.ext.security.ClaimRoles.at(claimPath);
    }

    public static ClaimRoles at(String claimPath) {
        return new ClaimRoles(claimPath);
    }

    public List<String> of(Principal principal) {
        return principal instanceof OidcPrincipal ? roles.of(principal) : List.of();
    }

    @Override
    public boolean hasRole(SecurityIdentity identity, String role, Target on) {
        return of(identity.principal()).contains(role);
    }
}
