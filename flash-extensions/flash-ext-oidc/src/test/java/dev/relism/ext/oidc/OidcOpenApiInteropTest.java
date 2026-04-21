package dev.relism.ext.oidc;

import dev.relism.ext.openapi.OpenApiContributor;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
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
        Map<Integer, String> responses = responses(AuthOnly.class);
        assertEquals("Authentication required", responses.get(401));
        assertFalse(responses.containsKey(403));
    }

    @Test
    void autoResponses_optionalAuth_addsNothing() throws Exception {
        Map<Integer, String> responses = responses(AuthOptional.class);
        assertTrue(responses.isEmpty());
    }

    @Test
    void autoResponses_oneRole_formatsSingular() throws Exception {
        Map<Integer, String> responses = responses(OneRole.class);
        assertEquals("Authentication required", responses.get(401));
        assertEquals("\"admin\" role required", responses.get(403));
    }

    @Test
    void autoResponses_multiRoles_formatsPlural() throws Exception {
        Map<Integer, String> responses = responses(MultiRole.class);
        assertEquals("Roles \"admin, operator\" are required", responses.get(403));
    }

    @Test
    void autoResponses_oneScope_formatsSingular() throws Exception {
        Map<Integer, String> responses = responses(OneScope.class);
        assertEquals("\"orders:write\" scope required", responses.get(403));
    }

    @Test
    void autoResponses_multiScopes_formatsPlural() throws Exception {
        Map<Integer, String> responses = responses(MultiScope.class);
        assertEquals("Scopes \"orders:write, payments:write\" are required", responses.get(403));
    }

    @Test
    void autoResponses_roleAndScope_combinesMessages() throws Exception {
        Map<Integer, String> responses = responses(RoleAndScope.class);
        assertEquals("\"admin\" role required; \"orders:write\" scope required", responses.get(403));
    }

    @Test
    void securityContribution_presentForAuthenticatedHandler() throws Exception {
        dev.relism.ext.openapi.OpenApiOperationContribution operation = contributor().operationFor(AuthOnly.class);
        List<Map<String, List<String>>> security = operation.security();
        assertEquals(1, security.size());
        assertTrue(security.getFirst().containsKey("issuer"));
    }

    private static OpenApiContributor contributor() throws Exception {
        Class<?> clazz = Class.forName("dev.relism.ext.oidc.OidcExtension$OpenApiIntegration");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();

        Method m = clazz.getDeclaredMethod("register", dev.relism.extension.FlashContext.class, OidcConfig.class, OidcProviderMetadata.class);
        m.setAccessible(true);

        dev.relism.extension.FlashContext ctx = new dev.relism.extension.FlashContext();
        dev.relism.ext.openapi.OpenApiContributorRegistry registry = new dev.relism.ext.openapi.OpenApiContributorRegistry();
        ctx.provide(dev.relism.ext.openapi.OpenApiContributorRegistry.class, registry);

        OidcConfig config = OidcConfig.builder("https://issuer", "c", "s", "/cb").build();
        OidcProviderMetadata meta = new OidcProviderMetadata("a", "t", "u", "j", "e");
        m.invoke(instance, ctx, config, meta);

        return registry.contributors().getFirst();
    }

    private static Map<Integer, String> responses(Class<?> cls) throws Exception {
        Map<Integer, dev.relism.ext.openapi.OpenApiResponseContribution> byCode = contributor().operationFor(cls).responses();
        java.util.LinkedHashMap<Integer, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, dev.relism.ext.openapi.OpenApiResponseContribution> e : byCode.entrySet()) {
            out.put(e.getKey(), e.getValue().description());
        }
        return out;
    }
}
