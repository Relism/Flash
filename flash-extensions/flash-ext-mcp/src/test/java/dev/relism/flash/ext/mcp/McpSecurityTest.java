package dev.relism.flash.ext.mcp;

import dev.relism.flash.ext.security.AuthenticationMechanism;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.App;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.SecurityScheme;
import dev.relism.flash.http.Request;
import dev.relism.flash.ext.security.apikey.ApiKey;
import dev.relism.flash.ext.security.apikey.ApiKeys;
import dev.relism.flash.ext.security.apikey.GeneratedApiKey;
import dev.relism.flash.ext.security.oidc.OidcModule;
import dev.relism.flash.ext.security.oidc.OidcProvider;
import dev.relism.flash.ext.security.test.FakeOidcProvider;
import dev.relism.flash.testing.FlashRequest;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertThrows;

class McpSecurityTest {

    static final FakeOidcProvider provider = start();
    static final GeneratedApiKey KEY = new ApiKeys<String>("mk", id -> null).generate();
    static final ApiKeys<String> apiKeys = new ApiKeys<>("mk", id -> id.equals(KEY.id()) ? new ApiKey<>(KEY.id(), KEY.secretHash(), "agent", null, null) : null);

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(new SecurityModule().roles((identity, role, on) -> identity.principal().name().equals(role + "@" + on.get("project"))),
                    new OidcModule(OidcProvider.of("fake", provider.issuer(), "app", "secret")),
                    apiKeys,
                    new McpModule(McpConfig.builder("secure").toolsPackage("dev.relism.flash.ext.mcp.authfixtures.secured")
                            .scopesSupported("openid", "email").build())));

    static final OidcModule oidc = new OidcModule(OidcProvider.of("fake", provider.issuer(), "app", "secret"));

    /** Another issuer the application trusts, which the restricted endpoint below must neither accept nor advertise. */
    static final AuthenticationMechanism elsewhere = new AuthenticationMechanism() {
        @Override public Principal authenticate(Request req) {
            return "Other".equals(req.header("Authorization")) ? () -> "other" : null;
        }
        @Override public List<SecurityScheme> schemes() {
            return List.of(SecurityScheme.openIdConnect("other", "https://other.example"));
        }
    };

    /** Only the OIDC provider authenticates this endpoint, however many mechanisms the application has. */
    @RegisterExtension
    static final FlashTest restricted = FlashTest.of(() -> App.create()
            .install(new SecurityModule(), oidc, apiKeys,
                    new McpModule(McpConfig.builder("restricted").toolsPackage("dev.relism.flash.ext.mcp.fixtures")
                            .mechanisms(oidc.oidc()).requireTokenAudience(false).build()))
            .add(AuthenticationMechanism.class, elsewhere));

    /** The same chain with the RFC 8707 check turned off, for an authorization server that cannot mint a resource audience. */
    @RegisterExtension
    static final FlashTest relaxed = FlashTest.of(() -> App.create()
            .install(new SecurityModule().roles((identity, role, on) -> false),
                    new OidcModule(OidcProvider.of("fake", provider.issuer(), "app", "secret")),
                    new McpModule(McpConfig.builder("relaxed").toolsPackage("dev.relism.flash.ext.mcp.authfixtures.secured")
                            .requireTokenAudience(false).build())));

    static FakeOidcProvider start() {
        try {
            return new FakeOidcProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String resource() {
        return "http://127.0.0.1:" + app.port() + "/mcp";
    }

    static FlashResponse call(Consumer<FlashRequest> credential, String method, String params) {
        return app.request().with(credential).header("Accept", "application/json")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"" + method + "\",\"params\":" + params + "}")
                .post("/mcp");
    }

    @Test
    void anAnonymousCallIsChallengedWithTheResourceMetadata() {
        call(request -> {}, "initialize", "{}").expectStatus(401)
                .expectHeader("WWW-Authenticate", "Bearer resource_metadata=\"http://127.0.0.1:" + app.port() + "/.well-known/oauth-protected-resource/mcp\"");
    }

    @Test
    void theProtectedResourceMetadataNamesTheIssuer() {
        app.get("/.well-known/oauth-protected-resource/mcp").expectStatus(200)
                .expectBody("{\"resource\":\"" + resource() + "\",\"authorization_servers\":[\"" + provider.issuer() + "\"],\"scopes_supported\":[\"openid\",\"email\"]}");
    }

    @Test
    void aTokenIsAcceptedOnlyForThisResource() {
        call(provider.bearer("u", Map.of("aud", resource())), "initialize", "{}").expectStatus(200).expectBodyContains("protocolVersion");
        call(provider.bearer("u", Map.of("aud", "https://elsewhere.example/mcp")), "initialize", "{}").expectStatus(403);
    }

    @Test
    void aTokenWithoutTheResourceAudienceIsAcceptedWhenTheCheckIsOff() {
        relaxed.request().with(provider.bearer("u", Map.of("aud", "https://elsewhere.example/mcp"))).header("Accept", "application/json")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}")
                .post("/mcp").expectStatus(200).expectBodyContains("protocolVersion");
    }

    /** The resource is the application's own origin: a forwarded header naming another host cannot make its tokens good here. */
    @Test
    void aForwardedHostCannotChooseTheResource() {
        app.request().with(provider.bearer("u", Map.of("aud", "https://evil.example/mcp")))
                .header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "evil.example").header("Accept", "application/json")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").post("/mcp").expectStatus(403);
    }

    @Test
    void anEndpointRestrictedToSomeMechanismsAcceptsAndAdvertisesOnlyThem() {
        restricted.get("/.well-known/oauth-protected-resource/mcp").expectStatus(200)
                .expectBody("{\"resource\":\"http://127.0.0.1:" + restricted.port() + "/mcp\",\"authorization_servers\":[\"" + provider.issuer() + "\"]}");
        Consumer<FlashRequest> key = request -> request.header("Authorization", "Bearer " + KEY.token());
        Consumer<FlashRequest> other = request -> request.header("Authorization", "Other");
        for (Consumer<FlashRequest> refused : List.of(key, other)) {
            restricted.request().with(refused).header("Accept", "application/json")
                    .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").post("/mcp").expectStatus(401);
        }
        restricted.request().with(provider.bearer("u", Map.of())).header("Accept", "application/json")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").post("/mcp").expectStatus(200);
    }

    /** An API key is not audience-bound: the same chain authenticates agents that never saw an authorization server. */
    @Test
    void anApiKeyIsAcceptedBesideOAuth() {
        call(request -> request.header("Authorization", "Bearer " + KEY.token()), "initialize", "{}").expectStatus(200);
    }

    @Test
    void toolPoliciesReadTheirTargetFromTheArguments() {
        Consumer<FlashRequest> admin = provider.bearer("admin@42", Map.of("aud", resource()));
        call(admin, "tools/call", "{\"name\":\"admin_only\",\"arguments\":{\"project\":\"42\"}}").expectBodyContains("\"isError\":false");
        call(admin, "tools/call", "{\"name\":\"admin_only\",\"arguments\":{\"project\":\"7\"}}").expectBodyContains("denied: missing role");
        call(provider.bearer("u", Map.of("aud", resource(), "scope", "write")), "tools/call", "{\"name\":\"write_only\"}").expectBodyContains("written");
        call(provider.bearer("u", Map.of("aud", resource())), "tools/call", "{\"name\":\"write_only\"}").expectBodyContains("denied: missing scope");
    }

    @Test
    void securityIsRequiredUnlessDeclaredOff() {
        FlashTest unsecured = FlashTest.of(() -> App.create().install(new McpModule(McpConfig.builder("x").toolsPackage("dev.relism.flash.ext.mcp.fixtures").build())));
        assertThrows(Exception.class, () -> unsecured.get("/mcp"));
        FlashTest contradictory = FlashTest.of(() -> App.create().install(new McpModule(McpConfig.builder("x")
                .toolsPackage("dev.relism.flash.ext.mcp.authfixtures.secured").security(McpSecurity.NONE).build())));
        assertThrows(Exception.class, () -> contradictory.get("/mcp"));
    }
}
