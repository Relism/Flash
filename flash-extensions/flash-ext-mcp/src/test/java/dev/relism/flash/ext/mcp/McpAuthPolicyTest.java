package dev.relism.flash.ext.mcp;

import dev.relism.flash.ext.oidc.OidcConfig;
import dev.relism.flash.ext.oidc.OidcExtension;
import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

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

    private static final FakeOidcProvider provider = newProvider();

    @RegisterExtension
    static FlashTest secured = FlashTest.of(app -> {
        app.install(new OidcExtension(OidcConfig.builder(
                provider.issuer(), "mcp-client", "secret", "/auth/callback").build()));
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(SECURED_TOOLS)
                .security(McpSecurity.REQUIRED)
                .build()));
    });

    /** Tokens are audience-bound to this server, so the port has to be read back after boot. */
    private static String resourceId() {
        return "http://127.0.0.1:" + secured.port() + "/mcp";
    }

    @AfterAll
    static void closeProvider() {
        provider.close();
    }

    // ── Tool policy ──────────────────────────────────────────────────────────

    @Test
    void rolesAllowed_deniesWithoutRole_allowsWithRole() throws Exception {
        callTool("admin_only", provider.signToken("user-1", resourceId(), null))
                .expectStatus(200)
                .expectBodyContains("\"isError\":true")
                .expectBodyContains("missing required role");

        callTool("admin_only", provider.signToken("user-1", resourceId(), null, "admin"))
                .expectStatus(200)
                .expectBodyContains("\"isError\":false")
                .expectBodyContains("ok");
    }

    @Test
    void scopesAllowed_deniesWithoutScope_allowsWithScope() throws Exception {
        callTool("write_only", provider.signToken("user-1", resourceId(), "read"))
                .expectStatus(200)
                .expectBodyContains("\"isError\":true")
                .expectBodyContains("missing required scope");

        callTool("write_only", provider.signToken("user-1", resourceId(), "read write"))
                .expectStatus(200)
                .expectBodyContains("\"isError\":false")
                .expectBodyContains("written");
    }

    @Test
    void unannotatedTool_unaffectedByOtherToolsPolicies() throws Exception {
        callTool("open", provider.signToken("user-1", resourceId(), null))
                .expectStatus(200)
                .expectBodyContains("\"isError\":false")
                .expectBodyContains("open");
    }

    private static FlashResponse callTool(String toolName, String token) {
        return secured.request()
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + token)
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\""
                        + toolName + "\"}}")
                .post("/mcp");
    }

    // ── Boot-time rejection ──────────────────────────────────────────────────
    // These assert that start() throws, so they build the app directly rather than through
    // FlashTest — a harness whose job is to boot an app is the wrong tool for asserting that
    // booting fails. Port 0 still removes the old free-port dance.

    private FlashApp bootFailure;

    @AfterEach
    void releaseBootFailureListener() {
        if (bootFailure != null) bootFailure.stop().join();
    }

    @Test
    void toolAnnotated_butSecurityNone_failsAtBoot() {
        bootFailure = mcpApp(SECURED_TOOLS, McpSecurity.NONE);

        IllegalStateException error = assertThrows(IllegalStateException.class, bootFailure::start);
        assertTrue(error.getMessage().contains("no active OAuth2 protection"), error.getMessage());
    }

    @Test
    void bareAuthenticated_hasNoEffect_failsAtBoot() {
        bootFailure = mcpApp(AUTHENTICATED_ONLY_TOOLS, McpSecurity.REQUIRED);

        IllegalStateException error = assertThrows(IllegalStateException.class, bootFailure::start);
        assertTrue(error.getMessage().contains("no effect"), error.getMessage());
    }

    private static FlashApp mcpApp(String toolsPackage, McpSecurity security) {
        FlashApp app = FlashApp.create(FlashConfiguration.builder()
                .port(0).host("127.0.0.1").shutdownDrainTimeoutMs(250).build());
        app.install(new OidcExtension(OidcConfig.builder(
                provider.issuer(), "mcp-client", "secret", "/auth/callback").build()));
        app.install(new McpExtension(McpConfig.builder("secure-server")
                .toolsPackage(toolsPackage)
                .security(security)
                .build()));
        return app;
    }

    private static FakeOidcProvider newProvider() {
        try {
            return new FakeOidcProvider();
        } catch (Exception failure) {
            throw new IllegalStateException("Could not start the fake OIDC provider", failure);
        }
    }
}
