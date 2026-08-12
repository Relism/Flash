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
 * Exercises the actual OAuth2 resolution rules against a real {@code flash-ext-oidc}
 * installation backed by {@link FakeOidcProvider} — real discovery, real JWKS, real RS256
 * tokens — plus the fail-fast/degrade behavior when oidc is absent.
 */
class McpExtensionSecurityTest {

    private static final String TOOLS_PACKAGE = "dev.relism.flash.ext.mcp.fixtures";

    private FlashApp app;
    private FakeOidcProvider provider;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
        if (provider != null) provider.close();
    }

    @Test
    void required_withoutOidc_throwsAtBoot() throws Exception {
        int port = freePort();
        app = FlashApp.create(port);
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(TOOLS_PACKAGE)
                .security(McpSecurity.REQUIRED)
                .build()));

        assertThrows(IllegalStateException.class, () -> app.start());
    }

    @Test
    void auto_withoutOidc_degradesToPublic() throws Exception {
        int port = freePort();
        app = FlashApp.create(port);
        app.install(new McpExtension(McpConfig.builder("auto-server")
                .toolsPackage(TOOLS_PACKAGE)
                .security(McpSecurity.AUTO)
                .build()));
        app.start();

        HttpResponse<String> resp = post(port, initializeBody(), null);
        assertEquals(200, resp.statusCode());
    }

    @Test
    void required_withOidc_rejectsMissingToken() throws Exception {
        int port = bootSecuredApp(null);

        HttpResponse<String> resp = post(port, initializeBody(), null);
        assertEquals(401, resp.statusCode());
    }

    @Test
    void required_withOidc_rejectsWrongAudience() throws Exception {
        int port = bootSecuredApp("https://mcp.example.com/mcp");
        String token = provider.signToken("user-1", "https://someone-else.example.com/resource");

        HttpResponse<String> resp = post(port, initializeBody(), token);
        assertEquals(403, resp.statusCode());
    }

    @Test
    void required_withOidc_acceptsValidAudience() throws Exception {
        String resourceId = "https://mcp.example.com/mcp";
        int port = bootSecuredApp(resourceId);
        String token = provider.signToken("user-1", resourceId);

        HttpResponse<String> resp = post(port, initializeBody(), token);
        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"protocolVersion\""));
    }

    @Test
    void required_withOidc_noExplicitResourceIdentifier_derivesFromRequestAndEnforcesAudience() throws Exception {
        int port = bootSecuredApp(null);
        String derivedResourceId = "http://127.0.0.1:" + port + "/mcp";

        String matching = provider.signToken("user-1", derivedResourceId);
        assertEquals(200, post(port, initializeBody(), matching).statusCode());

        String mismatched = provider.signToken("user-1", "https://someone-else.example.com/resource");
        assertEquals(403, post(port, initializeBody(), mismatched).statusCode());
    }

    @Test
    void required_withOidc_missingToken_challengeIncludesResourceMetadata() throws Exception {
        int port = bootSecuredApp(null);

        HttpResponse<String> resp = post(port, initializeBody(), null);
        assertEquals(401, resp.statusCode());
        String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
        assertTrue(challenge.contains(
                "resource_metadata=\"http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource/mcp\""),
                "WWW-Authenticate: " + challenge);
    }

    @Test
    void required_withOidc_noExplicitConfig_publishesProtectedResourceMetadata() throws Exception {
        int port = bootSecuredApp(null);

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"resource\":\"http://127.0.0.1:" + port + "/mcp\""), resp.body());
        assertTrue(resp.body().contains("\"authorization_servers\":[\"" + provider.issuer() + "\"]"), resp.body());
        assertTrue(!resp.body().contains("scopes_supported"), "scopes_supported must be omitted when unset: " + resp.body());
    }

    @Test
    void scopesSupported_published_inProtectedResourceMetadata() throws Exception {
        provider = new FakeOidcProvider();
        int port = freePort();

        app = FlashApp.create(port);
        app.install(new OidcExtension(OidcConfig.builder(
                provider.issuer(), "mcp-client", "secret", "/auth/callback").build()));
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(TOOLS_PACKAGE)
                .security(McpSecurity.REQUIRED)
                .scopesSupported("openid", "profile", "email")
                .build()));
        app.start();

        HttpResponse<String> resp = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(
                        "http://127.0.0.1:" + port + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"scopes_supported\":[\"openid\",\"profile\",\"email\"]"), resp.body());
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private int bootSecuredApp(String resourceIdentifier) throws Exception {
        provider = new FakeOidcProvider();
        int port = freePort();

        OidcConfig oidcConfig = OidcConfig.builder(
                        provider.issuer(), "mcp-client", "secret", "/auth/callback")
                .build();

        var mcpBuilder = McpConfig.builder("secure-server")
                .toolsPackage(TOOLS_PACKAGE)
                .security(McpSecurity.REQUIRED);
        if (resourceIdentifier != null) mcpBuilder.resourceIdentifier(resourceIdentifier);

        app = FlashApp.create(port);
        app.install(new OidcExtension(oidcConfig));
        app.install(new McpExtension(mcpBuilder.build()));
        app.start();
        return port;
    }

    private static String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static HttpResponse<String> post(int port, String body, String bearerToken) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (bearerToken != null) req.header("Authorization", "Bearer " + bearerToken);
        return HttpClient.newHttpClient().send(req.build(), HttpResponse.BodyHandlers.ofString());
    }
}
