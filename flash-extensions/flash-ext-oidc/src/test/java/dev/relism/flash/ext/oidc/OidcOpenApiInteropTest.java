package dev.relism.flash.ext.oidc;

import dev.relism.flash.ext.openapi.OpenApiContributorRegistry;
import dev.relism.flash.ext.openapi.OpenApiOperationContribution;
import dev.relism.flash.ext.openapi.OpenApiResponseContribution;
import dev.relism.flash.ext.openapi.OpenApiContributor;
import dev.relism.flash.extension.FlashContext;
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
        OpenApiOperationContribution operation = contributor().operationFor(AuthOnly.class);
        List<Map<String, List<String>>> security = operation.security();
        assertEquals(1, security.size());
        assertTrue(security.getFirst().containsKey("issuer"));
    }

    private static OpenApiContributor contributor() throws Exception {
        Class<?> clazz = Class.forName("dev.relism.flash.ext.oidc.OidcExtension$OpenApiIntegration");
        Constructor<?> ctor = clazz.getDeclaredConstructor();
        ctor.setAccessible(true);
        Object instance = ctor.newInstance();

        Method m = clazz.getDeclaredMethod("register", FlashContext.class, OidcConfig.class, OidcProviderMetadata.class);
        m.setAccessible(true);

        FlashContext ctx = new FlashContext();
        OpenApiContributorRegistry registry = new OpenApiContributorRegistry();
        ctx.provide(OpenApiContributorRegistry.class, registry);

        OidcConfig config = OidcConfig.builder("https://issuer", "c", "s", "/cb").build();
        OidcProviderMetadata meta = new OidcProviderMetadata("a", "t", "u", "j", "e");
        m.invoke(instance, ctx, config, meta);

        return registry.contributors().getFirst();
    }

    private static Map<Integer, String> responses(Class<?> cls) throws Exception {
        Map<Integer, OpenApiResponseContribution> byCode = contributor().operationFor(cls).responses();
        java.util.LinkedHashMap<Integer, String> out = new java.util.LinkedHashMap<>();
        for (Map.Entry<Integer, OpenApiResponseContribution> e : byCode.entrySet()) {
            out.put(e.getKey(), e.getValue().description());
        }
        return out;
    }
}
