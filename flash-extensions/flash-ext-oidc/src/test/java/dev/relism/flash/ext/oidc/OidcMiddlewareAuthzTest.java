package dev.relism.flash.ext.oidc;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class OidcMiddlewareAuthzTest {

    private static OidcMiddleware middleware(String rolesPath, String scopePaths) {
        OidcConfig cfg = OidcConfig.builder("https://idp.example.com", "client", "secret", "/auth/callback")
                .rolesClaimPath(rolesPath)
                .scopeClaimPaths(scopePaths)
                .build();
        return new OidcMiddleware(null, cfg, null, null);
    }

    @Test
    void rolesAllowed_readsConfiguredNestedClaimPath() {
        OidcMiddleware mw = middleware("realm_access.roles", "scope,scp");
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("user", "admin")));

        assertTrue(mw.rolesAllowed(claims, new String[]{"admin"}));
        assertFalse(mw.rolesAllowed(claims, new String[]{"ops"}));
    }

    @Test
    void scopesAllowed_all_requiresEveryScope() {
        OidcMiddleware mw = middleware("roles", "scope,scp");
        Map<String, Object> claims = Map.of("scope", "openid profile orders:read");

        assertTrue(mw.scopesAllowed(claims, new String[]{"openid", "orders:read"}, ScopesAllowed.Match.ALL));
        assertFalse(mw.scopesAllowed(claims, new String[]{"openid", "orders:write"}, ScopesAllowed.Match.ALL));
    }

    @Test
    void scopesAllowed_any_acceptsAnyConfiguredScopeSource() {
        OidcMiddleware mw = middleware("roles", "scope,scp,permissions.scopes");
        Map<String, Object> claims = Map.of(
                "scp", List.of("payments:write"),
                "permissions", Map.of("scopes", "orders:approve")
        );

        assertTrue(mw.scopesAllowed(claims, new String[]{"orders:approve", "orders:read"}, ScopesAllowed.Match.ANY));
        assertTrue(mw.scopesAllowed(claims, new String[]{"payments:write"}, ScopesAllowed.Match.ANY));
        assertFalse(mw.scopesAllowed(claims, new String[]{"unknown"}, ScopesAllowed.Match.ANY));
    }

    @Test
    void extractBearerToken_acceptsCaseInsensitiveBearerAndTrimsSpaces() {
        assertEquals("abc.def.ghi", OidcMiddleware.extractBearerToken("Bearer abc.def.ghi"));
        assertEquals("abc", OidcMiddleware.extractBearerToken("   bearer    abc   "));
        assertNull(OidcMiddleware.extractBearerToken("Basic Zm9vOmJhcg=="));
        assertNull(OidcMiddleware.extractBearerToken("Bearer"));
    }

    @Test
    void bearerChallenge_containsRealmAndRfcErrors() {
        OidcMiddleware mw = middleware("roles", "scope,scp");

        String basic = mw.bearerChallenge();
        String invalid = mw.invalidTokenChallenge();
        String insufficient = mw.insufficientScopeChallenge(new String[]{"orders:read", "payments:write"});

        assertTrue(basic.startsWith("Bearer realm=\""));
        assertTrue(invalid.contains("error=\"invalid_token\""));
        assertTrue(insufficient.contains("error=\"insufficient_scope\""));
        assertTrue(insufficient.contains("scope=\"orders:read payments:write\""));
    }
}
