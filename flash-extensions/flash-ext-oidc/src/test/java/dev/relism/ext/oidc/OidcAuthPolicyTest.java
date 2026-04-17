package dev.relism.ext.oidc;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OidcAuthPolicyTest {

    static class PlainHandler {}

    @Authenticated
    static class AuthenticatedHandler {}

    @Authenticated(optional = true)
    static class OptionalHandler {}

    @RolesAllowed({"admin", " editor ", "admin"})
    static class RolesHandler {}

    @ScopesAllowed(value = {"orders:write", " payments:write ", "orders:write"}, match = ScopesAllowed.Match.ANY)
    static class ScopesHandler {}

    @Authenticated
    @RolesAllowed("admin")
    @ScopesAllowed(value = {"orders:read", "payments:read"}, match = ScopesAllowed.Match.ALL)
    static class CombinedHandler {}

    @Authenticated(optional = true)
    @ScopesAllowed("orders:read")
    static class InvalidOptionalHandler {}

    @Test
    void compileFromAnnotations_noSecurityAnnotations_returnsNull() {
        assertNull(OidcAuthPolicy.compileFromAnnotations(PlainHandler.class));
    }

    @Test
    void compileFromAnnotations_authenticated_createsRequiredAuthPolicy() {
        OidcAuthPolicy policy = OidcAuthPolicy.compileFromAnnotations(AuthenticatedHandler.class);
        assertNotNull(policy);
        assertFalse(policy.optionalAuth());
        assertEquals(0, policy.requiredRoles().length);
        assertEquals(0, policy.requiredScopes().length);
    }

    @Test
    void compileFromAnnotations_optionalAuth_createsOptionalPolicy() {
        OidcAuthPolicy policy = OidcAuthPolicy.compileFromAnnotations(OptionalHandler.class);
        assertNotNull(policy);
        assertTrue(policy.optionalAuth());
    }

    @Test
    void compileFromAnnotations_rolesAndScopes_areNormalizedAndMerged() {
        OidcAuthPolicy policy = OidcAuthPolicy.compileFromAnnotations(CombinedHandler.class);
        assertNotNull(policy);
        assertFalse(policy.optionalAuth());
        assertArrayEquals(new String[]{"admin"}, policy.requiredRoles());
        assertArrayEquals(new String[]{"orders:read", "payments:read"}, policy.requiredScopes());
        assertEquals(ScopesAllowed.Match.ALL, policy.scopeMatch());
    }

    @Test
    void compileFromAnnotations_scopesAny_preservesMatchModeAndDedupes() {
        OidcAuthPolicy policy = OidcAuthPolicy.compileFromAnnotations(ScopesHandler.class);
        assertNotNull(policy);
        assertArrayEquals(new String[]{"orders:write", "payments:write"}, policy.requiredScopes());
        assertEquals(ScopesAllowed.Match.ANY, policy.scopeMatch());
    }

    @Test
    void compileFromAnnotations_optionalCannotBeCombinedWithConstraints() {
        assertThrows(IllegalStateException.class,
                () -> OidcAuthPolicy.compileFromAnnotations(InvalidOptionalHandler.class));
    }

    @Test
    void openApiScopesFor_returnsScopesWhenPresent() {
        assertEquals(List.of("orders:write", "payments:write"),
                OidcAuthPolicy.openApiScopesFor(ScopesHandler.class));
    }

    @Test
    void openApiScopesFor_rolesOnly_returnsEmptyList() {
        assertEquals(List.of(), OidcAuthPolicy.openApiScopesFor(RolesHandler.class));
    }

    @Test
    void openApiScopesFor_noSecurity_returnsNull() {
        assertNull(OidcAuthPolicy.openApiScopesFor(PlainHandler.class));
    }
}
