package dev.relism.flash.ext.mcp;

import dev.relism.flash.ext.oidc.OidcConfig;
import dev.relism.flash.ext.oidc.OidcExtension;
import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashApplication;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.testing.FlashRequest;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the actual OAuth2 resolution rules against a real {@code flash-ext-oidc}
 * installation backed by {@link FakeOidcProvider} — real discovery, real JWKS, real RS256
 * tokens — plus the fail-fast/degrade behavior when oidc is absent.
 *
 * <p>Four server configurations differ only in how MCP security is declared, so each gets its
 * own {@link FlashTest} and they share one provider.
 */
class McpExtensionSecurityTest {

    private static final String TOOLS_PACKAGE = "dev.relism.flash.ext.mcp.fixtures";
    private static final String EXPLICIT_RESOURCE_ID = "https://mcp.example.com/mcp";

    private static final FakeOidcProvider provider = newProvider();

    /** MCP asked for AUTO security with no oidc installed — should degrade to public. */
    @RegisterExtension
    static FlashTest degraded = FlashTest.of(app -> app.install(new McpExtension(
            McpConfig.builder("auto-server")
                    .toolsPackage(TOOLS_PACKAGE)
                    .security(McpSecurity.AUTO)
                    .build())));

    /** REQUIRED with oidc, resource identifier derived from the request. */
    @RegisterExtension
    static FlashTest secured = FlashTest.of(securedApp(null, null));

    /** REQUIRED with oidc and an explicitly declared resource identifier. */
    @RegisterExtension
    static FlashTest securedWithResourceId = FlashTest.of(securedApp(EXPLICIT_RESOURCE_ID, null));

    /** REQUIRED with oidc and advertised scopes. */
    @RegisterExtension
    static FlashTest securedWithScopes =
            FlashTest.of(securedApp(null, new String[] {"openid", "profile", "email"}));

    @AfterAll
    static void closeProvider() {
        provider.close();
    }

    // ── No oidc installed ────────────────────────────────────────────────────

    @Test
    void required_withoutOidc_throwsAtBoot() {
        // Asserting that boot fails, so this one builds its app directly rather than through
        // the harness; port(0) still removes the old free-port dance.
        FlashApp app = FlashApp.create(FlashConfiguration.builder()
                .port(0).host("127.0.0.1").shutdownDrainTimeoutMs(250).build());
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(TOOLS_PACKAGE)
                .security(McpSecurity.REQUIRED)
                .build()));
        try {
            assertThrows(IllegalStateException.class, app::start);
        } finally {
            app.stop().join();
        }
    }

    @Test
    void auto_withoutOidc_degradesToPublic() {
        post(degraded, initializeBody(), null).expectStatus(200);
    }

    // ── REQUIRED with oidc ───────────────────────────────────────────────────

    @Test
    void required_withOidc_rejectsMissingToken() {
        post(secured, initializeBody(), null).expectStatus(401);
    }

    @Test
    void required_withOidc_rejectsWrongAudience() throws Exception {
        String token = provider.signToken("user-1", "https://someone-else.example.com/resource");

        post(securedWithResourceId, initializeBody(), token).expectStatus(403);
    }

    @Test
    void required_withOidc_acceptsValidAudience() throws Exception {
        String token = provider.signToken("user-1", EXPLICIT_RESOURCE_ID);

        post(securedWithResourceId, initializeBody(), token)
                .expectStatus(200)
                .expectBodyContains("\"protocolVersion\"");
    }

    @Test
    void required_withOidc_noExplicitResourceIdentifier_derivesFromRequestAndEnforcesAudience() throws Exception {
        String derivedResourceId = "http://127.0.0.1:" + secured.port() + "/mcp";

        post(secured, initializeBody(), provider.signToken("user-1", derivedResourceId))
                .expectStatus(200);
        post(secured, initializeBody(), provider.signToken("user-1", "https://someone-else.example.com/resource"))
                .expectStatus(403);
    }

    @Test
    void required_withOidc_missingToken_challengeIncludesResourceMetadata() {
        FlashResponse response = post(secured, initializeBody(), null).expectStatus(401);

        String challenge = response.header("WWW-Authenticate");
        assertTrue(challenge != null && challenge.contains("resource_metadata=\"http://127.0.0.1:"
                        + secured.port() + "/.well-known/oauth-protected-resource/mcp\""),
                "WWW-Authenticate: " + challenge);
    }

    // ── Protected resource metadata ──────────────────────────────────────────

    @Test
    void required_withOidc_noExplicitConfig_publishesProtectedResourceMetadata() {
        FlashResponse response = secured.get("/.well-known/oauth-protected-resource/mcp")
                .expectStatus(200)
                .expectBodyContains("\"resource\":\"http://127.0.0.1:" + secured.port() + "/mcp\"")
                .expectBodyContains("\"authorization_servers\":[\"" + provider.issuer() + "\"]");

        assertTrue(!response.body().contains("scopes_supported"),
                "scopes_supported must be omitted when unset: " + response.body());
    }

    @Test
    void scopesSupported_published_inProtectedResourceMetadata() {
        securedWithScopes.get("/.well-known/oauth-protected-resource/mcp")
                .expectStatus(200)
                .expectBodyContains("\"scopes_supported\":[\"openid\",\"profile\",\"email\"]");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static FlashApplication securedApp(String resourceIdentifier, String[] scopesSupported) {
        return app -> {
            app.install(new OidcExtension(OidcConfig.builder(
                    provider.issuer(), "mcp-client", "secret", "/auth/callback").build()));

            McpConfig.Builder mcp = McpConfig.builder("secure-server")
                    .toolsPackage(TOOLS_PACKAGE)
                    .security(McpSecurity.REQUIRED);
            if (resourceIdentifier != null) mcp.resourceIdentifier(resourceIdentifier);
            if (scopesSupported != null) mcp.scopesSupported(scopesSupported);
            app.install(new McpExtension(mcp.build()));
        };
    }

    private static FlashResponse post(FlashTest server, String body, String bearerToken) {
        FlashRequest request = server.request().header("Accept", "application/json").json(body);
        if (bearerToken != null) request.header("Authorization", "Bearer " + bearerToken);
        return request.post("/mcp");
    }

    private static String initializeBody() {
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}";
    }

    private static FakeOidcProvider newProvider() {
        try {
            return new FakeOidcProvider();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not start the fake OIDC provider", failure);
        }
    }
}
