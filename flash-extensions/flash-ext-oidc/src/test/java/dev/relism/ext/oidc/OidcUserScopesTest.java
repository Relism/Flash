package dev.relism.ext.oidc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OidcUserScopesTest {

    @Test
    void scopes_readsStandardScopeString() {
        OidcUser user = new OidcUser(Map.of("scope", "openid profile orders:read"));

        assertEquals(List.of("openid", "profile", "orders:read"), user.scopes());
        assertTrue(user.hasScope("orders:read"));
        assertFalse(user.hasScope("orders:write"));
    }

    @Test
    void scopes_fallsBackToScpArray() {
        OidcUser user = new OidcUser(Map.of("scp", List.of("orders:write", "payments:write")));

        assertEquals(List.of("orders:write", "payments:write"), user.scopes());
        assertTrue(user.hasScope("payments:write"));
    }

    @Test
    void scopes_supportsCustomClaimPaths() {
        OidcUser user = new OidcUser(Map.of("permissions", Map.of("scopes", List.of("a", "b"))));

        assertEquals(List.of("a", "b"), user.scopes("permissions.scopes"));
        assertTrue(user.hasScope("permissions.scopes", "a"));
        assertFalse(user.hasScope("permissions.scopes", "x"));
    }

    @Test
    void scopes_combinesMultipleClaimPathsInOrder() {
        OidcUser user = new OidcUser(Map.of(
                "scope", "openid",
                "scp", List.of("profile", "orders:read")
        ));

        assertEquals(List.of("openid", "profile", "orders:read"), user.scopes("scope,scp"));
    }
}
