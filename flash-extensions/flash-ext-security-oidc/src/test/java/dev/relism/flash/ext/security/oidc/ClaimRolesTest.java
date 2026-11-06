package dev.relism.flash.ext.security.oidc;

import dev.relism.flash.ext.security.Principal;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SuppressWarnings("removal")
class ClaimRolesTest {

    /** The deprecated resolver keeps its promise: roles from OIDC tokens only, whatever else the app signs in with. */
    @Test
    void onlyAnOidcPrincipalsClaimsGiveRoles() {
        OidcPrincipal oidc = new OidcPrincipal("sso", "alice", Map.of("groups", List.of("admin")), "token", null, null);
        Principal other = new Principal() {
            @Override public String name() { return "mallory"; }
            @Override public Object claim(String name) { return List.of("admin"); }
        };
        assertEquals(List.of("admin"), ClaimRoles.at("groups").of(oidc));
        assertEquals(List.of(), ClaimRoles.at("groups").of(other));
    }
}
