package dev.relism.flash.ext.mcp;

import dev.relism.flash.ext.oidc.OidcConfig;
import dev.relism.flash.ext.oidc.OidcExtension;
import dev.relism.flash.extension.FlashApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @RolesAllowed}/{@code @ScopesAllowed} on an {@link McpTool} — see
 * {@link McpOidcIntegration#compileToolPolicy}. Same real-discovery/real-JWKS/real-RS256-token
 * approach as {@link McpExtensionSecurityTest}, against {@code fixtures.secured}'s tools.
 */
class McpAuthPolicyTest {

    private static final String SECURED_TOOLS = "dev.relism.flash.ext.mcp.authfixtures.secured";
    private static final String AUTHENTICATED_ONLY_TOOLS = "dev.relism.flash.ext.mcp.authfixtures.authenticatedonly";

    private FlashApp app;
    private FakeOidcProvider provider;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (provider != null) provider.close();
    }

    @Test
    void rolesAllowed_deniesWithoutRole_allowsWithRole() throws Exception {
        int port = bootSecuredApp(SECURED_TOOLS);
        String resourceId = "http://127.0.0.1:" + port + "/mcp";

        String noRole = provider.signToken("user-1", resourceId, null);
        HttpResponse<String> denied = callTool(port, "admin_only", noRole);
        assertEquals(200, denied.statusCode());
        assertTrue(denied.body().contains("\"isError\":true"), denied.body());
        assertTrue(denied.body().contains("missing required role"), denied.body());

        String withRole = provider.signToken("user-1", resourceId, null, "admin");
        HttpResponse<String> allowed = callTool(port, "admin_only", withRole);
        assertEquals(200, allowed.statusCode());
        assertTrue(allowed.body().contains("\"isError\":false"), allowed.body());
        assertTrue(allowed.body().contains("ok"), allowed.body());
    }

    @Test
    void scopesAllowed_deniesWithoutScope_allowsWithScope() throws Exception {
        int port = bootSecuredApp(SECURED_TOOLS);
        String resourceId = "http://127.0.0.1:" + port + "/mcp";

        String noScope = provider.signToken("user-1", resourceId, "read");
        HttpResponse<String> denied = callTool(port, "write_only", noScope);
        assertEquals(200, denied.statusCode());
        assertTrue(denied.body().contains("\"isError\":true"), denied.body());
        assertTrue(denied.body().contains("missing required scope"), denied.body());

        String withScope = provider.signToken("user-1", resourceId, "read write");
        HttpResponse<String> allowed = callTool(port, "write_only", withScope);
        assertEquals(200, allowed.statusCode());
        assertTrue(allowed.body().contains("\"isError\":false"), allowed.body());
        assertTrue(allowed.body().contains("written"), allowed.body());
    }

    @Test
    void unannotatedTool_unaffectedByOtherToolsPolicies() throws Exception {
        int port = bootSecuredApp(SECURED_TOOLS);
        String resourceId = "http://127.0.0.1:" + port + "/mcp";

        String plain = provider.signToken("user-1", resourceId, null);
        HttpResponse<String> resp = callTool(port, "open", plain);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"isError\":false"), resp.body());
        assertTrue(resp.body().contains("open"), resp.body());
    }

    @Test
    void toolAnnotated_butSecurityNone_failsAtBoot() throws Exception {
        provider = new FakeOidcProvider();
        int port = freePort();
        app = FlashApp.create(port);
        app.install(new OidcExtension(OidcConfig.builder(
                provider.issuer(), "mcp-client", "secret", "/auth/callback").build()));
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(SECURED_TOOLS)
                .security(McpSecurity.NONE)
                .build()));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> app.start());
        assertTrue(e.getMessage().contains("no active OAuth2 protection"), e.getMessage());
    }

    @Test
    void bareAuthenticated_hasNoEffect_failsAtBoot() throws Exception {
        provider = new FakeOidcProvider();
        int port = freePort();
        app = FlashApp.create(port);
        app.install(new OidcExtension(OidcConfig.builder(
                provider.issuer(), "mcp-client", "secret", "/auth/callback").build()));
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(AUTHENTICATED_ONLY_TOOLS)
                .security(McpSecurity.REQUIRED)
                .build()));

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> app.start());
        assertTrue(e.getMessage().contains("no effect"), e.getMessage());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int bootSecuredApp(String toolsPackage) throws Exception {
        provider = new FakeOidcProvider();
        int port = freePort();

        app = FlashApp.create(port);
        app.install(new OidcExtension(OidcConfig.builder(
                provider.issuer(), "mcp-client", "secret", "/auth/callback").build()));
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(toolsPackage)
                .security(McpSecurity.REQUIRED)
                .build()));
        app.start();
        return port;
    }

    private static HttpResponse<String> callTool(int port, String toolName, String token) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"" + toolName + "\"}}";
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }
}
