package dev.relism.flash.ext.security;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClaimRolesTest {

    record Claims(Map<String, Object> claims) implements Principal {
        @Override public String name() { return "alice"; }
        @Override public Object claim(String name) { return claims.get(name); }
    }

    @Test
    void anExactNameWinsOverADottedPath() {
        String groups = "http://schemas.microsoft.com/ws/2008/06/identity/claims/groups";
        assertEquals(List.of("admin"), ClaimRoles.at(groups).of(new Claims(Map.of(groups, List.of("admin")))));
    }

    @Test
    void aDottedPathWalksNestedClaims() {
        Claims keycloak = new Claims(Map.of("realm_access", Map.of("roles", Set.of("editor"))));
        assertEquals(List.of("editor"), ClaimRoles.at("realm_access.roles").of(keycloak));
    }

    /** A collection holding anything but strings gives no roles, rather than a List<String> that is not one. */
    @Test
    void aCollectionWithOtherThanStringsGivesNoRoles() {
        Claims mixed = new Claims(Map.of("groups", List.of(42, "admin")));
        assertEquals(List.of(), ClaimRoles.at("groups").of(mixed));
        assertEquals(false, ClaimRoles.at("groups").hasRole(new SecurityIdentity(mixed, null, null), "admin", Target.NONE));
    }

    @Test
    void aPrincipalWithoutClaimsHasNoRoles() {
        assertEquals(List.of(), ClaimRoles.at("groups").of(() -> "bob"));
        assertEquals(List.of(), ClaimRoles.at("groups").of(new Claims(Map.of("groups", "admin"))));
    }
}
