package dev.relism.ext.oidc;

import dev.relism.ext.openapi.OpenApiSecurityContributor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcOpenApiInteropTest {

    @Authenticated
    static class AuthOnly {}

    @Authenticated(optional = true)
    static class AuthOptional {}

    @RolesAllowed("admin")
    static class OneRole {}

    @RolesAllowed({"admin", "operator"})
    static class MultiRole {}

    @ScopesAllowed("orders:write")
    static class OneScope {}

    @ScopesAllowed({"orders:write", "payments:write"})
    static class MultiScope {}

    @RolesAllowed("admin")
    @ScopesAllowed("orders:write")
    static class RoleAndScope {}

    @Test
    void autoResponses_authOnly() throws Exception {
        Map<Integer, String> responses = contributor().autoResponsesFor(AuthOnly.class);
        assertEquals("Authentication required", responses.get(401));
        assertFalse(responses.containsKey(403));
    }

    @Test
    void autoResponses_optionalAuth_addsNothing() throws Exception {
        Map<Integer, String> responses = contributor().autoResponsesFor(AuthOptional.class);
        assertTrue(responses.isEmpty());
    }

    @Test
    void autoResponses_oneRole_formatsSingular() throws Exception {
        Map<Integer, String> responses = contributor().autoResponsesFor(OneRole.class);
        assertEquals("Authentication required", responses.get(401));
        assertEquals("\"admin\" role required", responses.get(403));
    }

    @Test
    void autoResponses_multiRoles_formatsPlural() throws Exception {
        Map<Integer, String> responses = contributor().autoResponsesFor(MultiRole.class);
        assertEquals("Roles \"admin, operator\" are required", responses.get(403));
    }

    @Test
    void autoResponses_oneScope_formatsSingular() throws Exception {
        Map<Integer, String> responses = contributor().autoResponsesFor(OneScope.class);
        assertEquals("\"orders:write\" scope required", responses.get(403));
    }

    @Test
    void autoResponses_multiScopes_formatsPlural() throws Exception {
        Map<Integer, String> responses = contributor().autoResponsesFor(MultiScope.class);
        assertEquals("Scopes \"orders:write, payments:write\" are required", responses.get(403));
    }

    @Test
    void autoResponses_roleAndScope_combinesMessages() throws Exception {
        Map<Integer, String> responses = contributor().autoResponsesFor(RoleAndScope.class);
        assertEquals("\"admin\" role required; \"orders:write\" scope required", responses.get(403));
    }

    private static OpenApiSecurityContributor contributor() throws Exception {
        Class<?> clazz = Class.forName("dev.relism.ext.oidc.OidcExtension$OpenApiIntegration");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();

        Method m = clazz.getDeclaredMethod("register", dev.relism.extension.FlashContext.class, OidcConfig.class, OidcProviderMetadata.class);
        m.setAccessible(true);

        dev.relism.extension.FlashContext ctx = new dev.relism.extension.FlashContext();
        dev.relism.ext.openapi.OpenApiSecurityRegistry registry = new dev.relism.ext.openapi.OpenApiSecurityRegistry();
        ctx.provide(dev.relism.ext.openapi.OpenApiSecurityRegistry.class, registry);

        OidcConfig config = OidcConfig.builder("https://issuer", "c", "s", "/cb").build();
        OidcProviderMetadata meta = new OidcProviderMetadata("a", "t", "u", "j", "e");
        m.invoke(instance, ctx, config, meta);

        return registry.contributors().getFirst();
    }
}
