package dev.relism.flash.ext.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityPolicyTest {

    static class Unannotated {}

    @PermitAll @RolesAllowed("ADMIN")
    static class Contradictory {}

    @RolesAllowed({})
    static class NoRoles {}

    @RolesAllowed("ADMIN") @ScopesAllowed("write")
    static class RolesAndScopes {}

    @Test
    void anUnannotatedTypeHasNoPolicy() {
        assertNull(SecurityPolicy.of(Unannotated.class));
    }

    @Test
    void contradictionsAndEmptyRequirementsFailAtCompileTime() {
        assertThrows(IllegalStateException.class, () -> SecurityPolicy.of(Contradictory.class));
        assertThrows(IllegalStateException.class, () -> SecurityPolicy.of(NoRoles.class));
    }

    @Test
    void rolesAndScopesBothRequireAuthentication() {
        SecurityPolicy policy = SecurityPolicy.of(RolesAndScopes.class);
        assertTrue(policy.required());
        assertTrue(policy.requiresRoles());
        assertFalse(SecurityPolicy.of(OpenAccess.class).required());
    }

    @PermitAll
    static class OpenAccess {}
}
