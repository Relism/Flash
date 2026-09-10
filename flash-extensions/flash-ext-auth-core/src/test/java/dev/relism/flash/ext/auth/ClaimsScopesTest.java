package dev.relism.flash.ext.auth;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClaimsScopesTest {

    @Test
    void scopes_readsStandardScopeString() {
        Claims user = new Claims(Map.of("scope", "openid profile orders:read"));

        assertEquals(List.of("openid", "profile", "orders:read"), user.scopes());
        assertTrue(user.hasScope("orders:read"));
        assertFalse(user.hasScope("orders:write"));
    }

    @Test
    void scopes_fallsBackToScpArray() {
        Claims user = new Claims(Map.of("scp", List.of("orders:write", "payments:write")));

        assertEquals(List.of("orders:write", "payments:write"), user.scopes());
        assertTrue(user.hasScope("payments:write"));
    }

    @Test
    void scopes_supportsCustomClaimPaths() {
        Claims user = new Claims(Map.of("permissions", Map.of("scopes", List.of("a", "b"))));

        assertEquals(List.of("a", "b"), user.scopes("permissions.scopes"));
        assertTrue(user.hasScope("permissions.scopes", "a"));
        assertFalse(user.hasScope("permissions.scopes", "x"));
    }

    @Test
    void scopes_combinesMultipleClaimPathsInOrder() {
        Claims user = new Claims(Map.of(
                "scope", "openid",
                "scp", List.of("profile", "orders:read")
        ));

        assertEquals(List.of("openid", "profile", "orders:read"), user.scopes("scope,scp"));
    }
}
