package dev.relism.flash.ext.auth;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Characterisation tests for claim matching — the part of authorization that has nothing to do
 * with OIDC: given a claims map, does the caller hold a role or a scope.
 *
 * <p>Written to pin the <em>current</em> behaviour, including the edges that are easy to change by
 * accident: which characters separate scopes in a string claim, whether a list entry is trimmed
 * before comparison, what an empty requirement means under each match mode, and how a claim path
 * that walks into a non-map resolves. Every assertion here reflects what the code does today, not
 * what it arguably should do.
 */
class ClaimMatchingTest {

    /** No credential source: every assertion here is about claims that are already resolved. */
    private static AuthMiddleware middleware(String rolesPath, String scopePaths) {
        return new AuthMiddleware(AuthConfig.builder()
                .rolesClaimPath(rolesPath)
                .scopeClaimPaths(scopePaths)
                .build(), null);
    }

    private static AuthMiddleware middleware() {
        return middleware("realm_access.roles", "scope,scp");
    }

    // ── Claim path traversal ─────────────────────────────────────────────────

    @Test
    void aPathWalksNestedMaps() {
        Map<String, Object> claims = Map.of("a", Map.of("b", Map.of("c", List.of("x"))));
        assertTrue(middleware("a.b.c", "scope").rolesAllowed(claims, new String[]{"x"}));
    }

    @Test
    void aPathThatWalksIntoANonMapResolvesToNothing() {
        // "a" is a string, so "a.b" has nowhere to go — not an error, just no match.
        Map<String, Object> claims = Map.of("a", "not-a-map");
        assertFalse(middleware("a.b", "scope").rolesAllowed(claims, new String[]{"anything"}));
    }

    @Test
    void aMissingPathResolvesToNothing() {
        assertFalse(middleware().rolesAllowed(Map.of("other", "value"), new String[]{"admin"}));
    }

    @Test
    void emptySegmentsInAPathAreSkipped() {
        // "realm_access..roles" collapses to the same two segments.
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("admin")));
        assertTrue(middleware("realm_access..roles", "scope").rolesAllowed(claims, new String[]{"admin"}));
    }

    @Test
    void segmentsAreTrimmed() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("admin")));
        assertTrue(middleware(" realm_access . roles ", "scope").rolesAllowed(claims, new String[]{"admin"}));
    }

    @Test
    void aBlankRolesPathIsRejectedAtConstruction() {
        assertThrows(IllegalStateException.class, () -> middleware("   ", "scope"));
    }

    @Test
    void aNullClaimValueResolvesToNothing() {
        Map<String, Object> nested = new HashMap<>();
        nested.put("roles", null);
        Map<String, Object> claims = Map.of("realm_access", nested);
        assertFalse(middleware().rolesAllowed(claims, new String[]{"admin"}));
    }

    // ── Roles: ANY semantics ─────────────────────────────────────────────────

    @Test
    void anyOneOfTheRequiredRolesIsEnough() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("user")));
        assertTrue(middleware().rolesAllowed(claims, new String[]{"admin", "user"}));
        assertFalse(middleware().rolesAllowed(claims, new String[]{"admin", "ops"}));
    }

    @Test
    void requiringNoRoleAtAllMatchesNothing() {
        // The loop never runs, so the answer is false even when the claim is present.
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("admin")));
        assertFalse(middleware().rolesAllowed(claims, new String[0]));
    }

    // ── What counts as "contains" ────────────────────────────────────────────

    @Test
    void aListClaimMatchesEntrywiseAndTrimsEachEntry() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("  admin  ", "user")));
        assertTrue(middleware().rolesAllowed(claims, new String[]{"admin"}));
    }

    @Test
    void aListEntryIsNeverSplitOnDelimiters() {
        // Unlike a string claim, a list entry is compared whole: "a b" is one role named "a b".
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", List.of("a b")));
        assertFalse(middleware().rolesAllowed(claims, new String[]{"a"}));
        assertTrue(middleware().rolesAllowed(claims, new String[]{"a b"}));
    }

    @Test
    void nullEntriesInAListAreSkipped() {
        Map<String, Object> claims = Map.of("realm_access",
                Map.of("roles", Arrays.asList(null, "admin")));
        assertTrue(middleware().rolesAllowed(claims, new String[]{"admin"}));
    }

    @Test
    void anArrayClaimBehavesLikeAList() {
        Map<String, Object> claims = Map.of("realm_access",
                Map.of("roles", (Object) new String[]{"admin", "user"}));
        assertTrue(middleware().rolesAllowed(claims, new String[]{"user"}));
    }

    @Test
    void aScalarClaimIsComparedWhole() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", 42));
        assertTrue(middleware().rolesAllowed(claims, new String[]{"42"}));
    }

    @Test
    void aStringClaimIsSplitOnSpacesTabsNewlinesAndCommas() {
        for (String separator : List.of(" ", "\t", "\n", "\r", ",")) {
            Map<String, Object> claims = Map.of("realm_access",
                    Map.of("roles", "admin" + separator + "user"));
            assertTrue(middleware().rolesAllowed(claims, new String[]{"user"}),
                    "separator " + separator.strip().isEmpty() + " should split the claim");
        }
    }

    @Test
    void aStringClaimDoesNotMatchAPrefixOrASubstring() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", "administrator"));
        assertFalse(middleware().rolesAllowed(claims, new String[]{"admin"}));
    }

    @Test
    void repeatedDelimitersProduceNoEmptyTokens() {
        Map<String, Object> claims = Map.of("realm_access", Map.of("roles", "  ,, admin ,,  "));
        assertTrue(middleware().rolesAllowed(claims, new String[]{"admin"}));
    }

    // ── Scopes: ALL vs ANY, across several claim paths ───────────────────────

    @Test
    void allRequiresEveryScope() {
        Map<String, Object> claims = Map.of("scope", "openid orders:read");
        assertTrue(middleware().scopesAllowed(claims, new String[]{"openid", "orders:read"}, ScopesAllowed.Match.ALL));
        assertFalse(middleware().scopesAllowed(claims, new String[]{"openid", "orders:write"}, ScopesAllowed.Match.ALL));
    }

    @Test
    void anyRequiresOne() {
        Map<String, Object> claims = Map.of("scope", "openid");
        assertTrue(middleware().scopesAllowed(claims, new String[]{"nope", "openid"}, ScopesAllowed.Match.ANY));
        assertFalse(middleware().scopesAllowed(claims, new String[]{"nope", "neither"}, ScopesAllowed.Match.ANY));
    }

    @Test
    void requiringNoScopeIsVacuouslyTrueUnderAllAndFalseUnderAny() {
        // The asymmetry falls out of the loops and is load-bearing for @ScopesAllowed's validation,
        // which rejects an empty value list before it can ever reach here.
        Map<String, Object> claims = Map.of("scope", "openid");
        assertTrue(middleware().scopesAllowed(claims, new String[0], ScopesAllowed.Match.ALL));
        assertFalse(middleware().scopesAllowed(claims, new String[0], ScopesAllowed.Match.ANY));
    }

    @Test
    void scopesAreLookedForInEveryConfiguredPathUntilOneMatches() {
        AuthMiddleware mw = middleware("roles", "scope, scp , permissions.scopes");
        Map<String, Object> claims = Map.of(
                "scp", List.of("payments:write"),
                "permissions", Map.of("scopes", "orders:approve"));

        assertTrue(mw.scopesAllowed(claims, new String[]{"payments:write"}, ScopesAllowed.Match.ALL));
        assertTrue(mw.scopesAllowed(claims, new String[]{"orders:approve"}, ScopesAllowed.Match.ALL));
        // ALL is satisfied even when the two scopes come from different claims.
        assertTrue(mw.scopesAllowed(claims,
                new String[]{"payments:write", "orders:approve"}, ScopesAllowed.Match.ALL));
    }

    @Test
    void blankScopePathsFallBackToScopeAndScp() {
        AuthMiddleware mw = middleware("roles", "   ");
        assertTrue(mw.scopesAllowed(Map.of("scope", "a"), new String[]{"a"}, ScopesAllowed.Match.ALL));
        assertTrue(mw.scopesAllowed(Map.of("scp", "b"), new String[]{"b"}, ScopesAllowed.Match.ALL));
    }

    @Test
    void aScopePathListOfOnlySeparatorsFallsBackToScopeAndScp() {
        AuthMiddleware mw = middleware("roles", " , , ");
        assertTrue(mw.scopesAllowed(Map.of("scp", "b"), new String[]{"b"}, ScopesAllowed.Match.ALL));
    }
}
