package dev.relism.flash.ext.security.oauthserver;

import com.nimbusds.jose.util.JSONObjectUtils;
import com.sun.net.httpserver.HttpServer;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.ext.cache.caffeine.CaffeineCacheModule;
import dev.relism.flash.ext.mcp.McpConfig;
import dev.relism.flash.ext.mcp.McpModule;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.App;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.ext.security.test.OAuthTestClient;
import dev.relism.flash.ext.security.test.TestSecurity;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthServerTest {

    static final Principal ALICE = () -> "alice";
    static final SecurityModule securityModule = new SecurityModule().loginPage("/login");
    static final Security security = securityModule.security();
    static final OAuthServer server = new OAuthServer("/mcp", "/api").scopes("read", "write").allowLocalClients()
            .subjects(identity -> new OAuthSubject(identity.principal().name(), Map.of("tenant", "acme")));

    static final TestSecurity users = new TestSecurity();

    /** /api only takes this server's tokens; /mcp is an OAuth-protected MCP endpoint trusting nothing else. */
    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(securityModule, new CaffeineCacheModule(), server, users)
            .install(new McpModule(McpConfig.builder("test").toolsPackage("dev.relism.flash.ext.security.oauthserver.tools").mechanisms(server).build()))
            .get("/api/me", (req, res) -> {
                OAuthPrincipal principal = SecurityIdentity.current().principal(OAuthPrincipal.class);
                return principal.name() + " " + principal.claim("tenant") + " " + principal.claim("scope");
            }, security.enforce(SecurityPolicy.AUTHENTICATED, (req, res) -> { throw HttpException.unauthorized(); }, List.of(server))));

    String origin() {
        return "http://127.0.0.1:" + app.port();
    }

    @Test
    void theMetadataDescribesACompliantServer() {
        app.get("/.well-known/oauth-authorization-server").expectStatus(200)
                .expectBodyContains("\"issuer\":\"" + origin() + "\"")
                .expectBodyContains("\"code_challenge_methods_supported\":[\"S256\"]")
                .expectBodyContains("\"authorization_response_iss_parameter_supported\":true")
                .expectBodyContains("\"client_id_metadata_document_supported\":true");
        // A browser client reads it cross-origin, preflight included.
        app.request().header("Origin", "https://client.example").get("/.well-known/oauth-authorization-server")
                .expectHeader("Access-Control-Allow-Origin", "*");
        app.request().header("Origin", "https://client.example").header("Access-Control-Request-Method", "POST")
                .options("/oauth/token").expectStatus(204).expectHeader("Access-Control-Allow-Headers", "Authorization, Content-Type");
        app.get("/oauth/jwks").expectStatus(200).expectBodyContains("\"crv\":\"P-256\"");
        assertTrue(!app.get("/oauth/jwks").body().contains("\"d\""), "the private key never leaves");
    }

    /** What an MCP client does end to end: discovery from the resource, registration, consent, and a token for this endpoint only. */
    @Test
    void anMcpClientIsSentHereAndItsTokenWorksOnTheEndpoint() {
        app.get("/.well-known/oauth-protected-resource/mcp").expectStatus(200)
                .expectBody("{\"resource\":\"" + origin() + "/mcp\",\"authorization_servers\":[\"" + origin() + "\"]}");

        OAuthTestClient.Tokens tokens = OAuthTestClient.register(app, users).authorize(ALICE);
        app.request().with(tokens.bearer()).header("Accept", "application/json")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"whoami\"}}")
                .post("/mcp").expectStatus(200).expectBodyContains("alice");
        // The default resource is /mcp: a token is good under the resource it was issued for and nowhere else.
        app.request().with(tokens.bearer()).get("/api/me").expectStatus(401);
        OAuthTestClient.Tokens api = OAuthTestClient.register(app, users).authorize(ALICE, Map.of("resource", origin() + "/api"));
        app.request().with(api.bearer()).get("/api/me").expectStatus(200);
        app.request().with(api.bearer()).header("Accept", "application/json")
                .json("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}").post("/mcp").expectStatus(401);
    }

    @Test
    void consentIsAskedOnceAndTheSubjectsClaimsTravel() {
        OAuthTestClient client = OAuthTestClient.register(app, users);
        OAuthTestClient.Tokens tokens = client.authorize(ALICE, Map.of("scope", "read admin", "resource", origin() + "/api"));
        assertEquals("read", tokens.scope(), "a scope this server does not grant is left out");
        app.request().with(tokens.bearer()).get("/api/me").expectStatus(200).expectBody("alice acme read");

        String authorize = "/oauth/authorize?response_type=code&client_id=" + client.clientId() + "&code_challenge=x&code_challenge_method=S256&scope=read";
        assertTrue(app.request().with(users.as(ALICE)).get(authorize).expectStatus(302).header("Location").startsWith(OAuthTestClient.REDIRECT_URI));
        assertTrue(app.request().with(users.as(ALICE)).get(authorize + "%20write").expectStatus(302).header("Location").startsWith("/consent?"),
                "more than was consented to is asked again");
        app.request().with(users.as(ALICE)).get("/oauth/authorize/request?" + authorize.substring(authorize.indexOf('?') + 1))
                .expectStatus(200).expectBodyContains("\"client_name\":\"Test client\"");
    }

    @Test
    void aBrowserWithoutASessionSignsInFirst() {
        app.request().header("Accept", "text/html").get("/oauth/authorize?client_id=x").expectStatus(302)
                .expectHeader("Location", "/login?redirect=%2Foauth%2Fauthorize%3Fclient_id%3Dx");
    }

    /** OAuth 2.1 §4.1.2.1: an unknown client or redirect URI is never redirected to; anything else is reported there. */
    @Test
    void refusalsGoToTheRedirectUriOnlyOnceItIsTrusted() {
        String client = OAuthTestClient.register(app, users).clientId();
        app.request().with(users.as(ALICE)).get("/oauth/authorize?response_type=code&client_id=nobody").expectStatus(400);
        app.request().with(users.as(ALICE)).get("/oauth/authorize?response_type=code&client_id=" + client + "&redirect_uri=https%3A%2F%2Fevil.example%2F")
                .expectStatus(400);
        String plain = app.request().with(users.as(ALICE))
                .get("/oauth/authorize?response_type=code&client_id=" + client + "&state=s&code_challenge=x&code_challenge_method=plain")
                .expectStatus(302).header("Location");
        assertEquals(OAuthTestClient.REDIRECT_URI + "?error=invalid_request&state=s&iss=" + encode(origin()), plain);
        String elsewhere = app.request().with(users.as(ALICE))
                .get("/oauth/authorize?response_type=code&client_id=" + client + "&code_challenge=x&code_challenge_method=S256&resource=https%3A%2F%2Fother.example%2Fmcp")
                .expectStatus(302).header("Location");
        assertTrue(elsewhere.contains("error=invalid_target"), elsewhere);
        // RFC 8252: a loopback redirect may come back on any port.
        assertTrue(app.request().with(users.as(ALICE))
                .get("/oauth/authorize?response_type=code&client_id=" + client + "&redirect_uri=http%3A%2F%2F127.0.0.1%3A53123%2Fcallback&code_challenge=x&code_challenge_method=S256")
                .expectStatus(302).header("Location").startsWith("/consent?"));
    }

    @Test
    void aDeniedConsentAndAForeignFormAreRefused() {
        String client = OAuthTestClient.register(app, users).clientId();
        String query = "response_type=code&client_id=" + client + "&code_challenge=x&code_challenge_method=S256";
        app.request().with(users.as(ALICE)).header("Content-Type", "application/x-www-form-urlencoded").body(query + "&consent=deny")
                .post("/oauth/authorize").expectStatus(303).expectHeader("Location", OAuthTestClient.REDIRECT_URI + "?error=access_denied&iss=" + encode(origin()));
        app.request().with(users.as(ALICE)).header("Origin", "https://evil.example").header("Content-Type", "application/x-www-form-urlencoded")
                .body(query + "&consent=allow").post("/oauth/authorize").expectStatus(403);
    }

    /** A refresh token is spent by its use; spending it twice means it leaked, and the whole authorization goes. */
    @Test
    void refreshTokensRotateAndAReuseRevokesTheFamily() {
        OAuthTestClient client = OAuthTestClient.register(app, users);
        OAuthTestClient.Tokens first = client.authorize(ALICE);
        OAuthTestClient.Tokens second = client.refresh(first.refreshToken());
        assertNotEquals(first.refreshToken(), second.refreshToken());

        token("grant_type=refresh_token&client_id=" + client.clientId() + "&refresh_token=" + first.refreshToken())
                .expectStatus(400).expectBodyContains("invalid_grant");
        token("grant_type=refresh_token&client_id=" + client.clientId() + "&refresh_token=" + second.refreshToken())
                .expectStatus(400).expectBodyContains("invalid_grant");
    }

    @Test
    void aRevokedRefreshTokenStopsWorking() {
        OAuthTestClient client = OAuthTestClient.register(app, users);
        OAuthTestClient.Tokens tokens = client.authorize(ALICE);
        app.request().header("Content-Type", "application/x-www-form-urlencoded")
                .body("client_id=" + client.clientId() + "&token=" + tokens.refreshToken()).post("/oauth/revoke").expectStatus(200);
        token("grant_type=refresh_token&client_id=" + client.clientId() + "&refresh_token=" + tokens.refreshToken()).expectStatus(400);
    }

    @Test
    void aClientRegisteringItselfIsHeldToTheRules() {
        register("{\"redirect_uris\":[\"http://evil.example/cb\"]}").expectStatus(400).expectBodyContains("invalid_redirect_uri");
        register("{\"redirect_uris\":[\"https://app.example/cb#x\"]}").expectStatus(400).expectBodyContains("invalid_redirect_uri");
        register("{\"grant_types\":[\"client_credentials\"],\"token_endpoint_auth_method\":\"client_secret_basic\"}")
                .expectStatus(400).expectBodyContains("invalid_client_metadata");
        register("{\"redirect_uris\":[\"https://app.example/cb\"]}").expectStatus(201)
                .expectBodyContains("\"token_endpoint_auth_method\":\"client_secret_basic\"").expectBodyContains("\"client_secret\"");
    }

    /** A machine the application registered itself: its secret in Basic, its own id as the subject, no refresh token. */
    @Test
    void aConfidentialClientGetsATokenForItself() throws Exception {
        OAuthServer.Registration machine = server.register(Map.of("client_name", "Sync job", "grant_types", List.of("client_credentials"),
                "token_endpoint_auth_method", "client_secret_basic"));
        String basic = java.util.Base64.getEncoder().encodeToString((machine.client().id() + ":" + machine.secret()).getBytes(StandardCharsets.UTF_8));
        Map<String, Object> response = JSONObjectUtils.parse(app.request().header("Authorization", "Basic " + basic)
                .header("Content-Type", "application/x-www-form-urlencoded").body("grant_type=client_credentials&scope=write&resource=" + encode(origin() + "/api"))
                .post("/oauth/token").expectStatus(200).expectHeader("Cache-Control", "no-store").body());
        assertNull(response.get("refresh_token"));
        app.request().header("Authorization", "Bearer " + response.get("access_token")).get("/api/me").expectStatus(200)
                .expectBody(machine.client().id() + " null write");

        String wrong = java.util.Base64.getEncoder().encodeToString((machine.client().id() + ":nope").getBytes(StandardCharsets.UTF_8));
        app.request().header("Authorization", "Basic " + wrong).header("Content-Type", "application/x-www-form-urlencoded")
                .body("grant_type=client_credentials").post("/oauth/token").expectStatus(401).expectBodyContains("invalid_client");
    }

    /** A code is good once, with its own verifier; a replay revokes what the first exchange issued. */
    @Test
    void aCodeNeedsItsVerifierAndCannotBeReplayed() {
        OAuthTestClient client = OAuthTestClient.register(app, users);
        client.authorize(ALICE);
        String authorize = "/oauth/authorize?response_type=code&client_id=" + client.clientId() + "&code_challenge=" + s256("right") + "&code_challenge_method=S256";
        String location = app.request().with(users.as(ALICE)).get(authorize).expectStatus(302).header("Location");
        String code = location.replaceAll(".*code=([^&]+).*", "$1");
        String exchange = "grant_type=authorization_code&client_id=" + client.clientId() + "&code=" + code + "&code_verifier=";

        token(exchange + "wrong").expectStatus(400).expectBodyContains("invalid_grant");
        String code2 = app.request().with(users.as(ALICE)).get(authorize).expectStatus(302).header("Location").replaceAll(".*code=([^&]+).*", "$1");
        String issued = token("grant_type=authorization_code&client_id=" + client.clientId() + "&code=" + code2 + "&code_verifier=right").expectStatus(200).body();
        String refresh = issued.replaceAll(".*\"refresh_token\":\"([^\"]+)\".*", "$1");
        token("grant_type=authorization_code&client_id=" + client.clientId() + "&code=" + code2 + "&code_verifier=right").expectStatus(400);
        token("grant_type=refresh_token&client_id=" + client.clientId() + "&refresh_token=" + refresh).expectStatus(400);
    }

    @Test
    void aForgedTokenIsInvalidAndOtherBearersAreNotThisServers() {
        OAuthTestClient.Tokens tokens = OAuthTestClient.register(app, users).authorize(ALICE, Map.of("resource", origin() + "/api"));
        String forged = tokens.accessToken().substring(0, tokens.accessToken().length() - 4) + "AAAA";
        app.request().header("Authorization", "Bearer " + forged).get("/api/me").expectStatus(401);
        app.request().header("Authorization", "Bearer gk_not.a-jwt").get("/api/me").expectStatus(401);
    }

    /** MCP's preferred registration: the client is the https URL of its own metadata document. */
    @Test
    void aClientMetadataDocumentIsAClient() throws Exception {
        HttpServer host = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String url = "http://127.0.0.1:" + host.getAddress().getPort() + "/client.json";
        String liar = "http://127.0.0.1:" + host.getAddress().getPort() + "/liar.json";
        serve(host, "/client.json", "{\"client_id\":\"" + url + "\",\"client_name\":\"Documented\",\"redirect_uris\":[\"" + OAuthTestClient.REDIRECT_URI + "\"]}");
        serve(host, "/liar.json", "{\"client_id\":\"https://someone.else/client.json\",\"redirect_uris\":[\"" + OAuthTestClient.REDIRECT_URI + "\"]}");
        host.start();
        try {
            String query = "response_type=code&code_challenge=x&code_challenge_method=S256&client_id=";
            app.request().with(users.as(ALICE)).get("/oauth/authorize/request?" + query + encode(url)).expectStatus(200)
                    .expectBodyContains("\"client_name\":\"Documented\"");
            app.request().with(users.as(ALICE)).get("/oauth/authorize?" + query + encode(liar)).expectStatus(400);
            // A document that fails is remembered as failing: naming it again fetches nothing.
            app.request().with(users.as(ALICE)).get("/oauth/authorize?" + query + encode(liar)).expectStatus(400);
            assertEquals(1, fetched.get(liar));
        } finally {
            host.stop(0);
        }
    }

    private static FlashResponse token(String form) {
        return app.request().header("Content-Type", "application/x-www-form-urlencoded").body(form).post("/oauth/token");
    }

    private static FlashResponse register(String json) {
        return app.request().header("Content-Type", "application/json").body(json).post("/oauth/register");
    }

    static final Map<String, Integer> fetched = new java.util.concurrent.ConcurrentHashMap<>();

    private static void serve(HttpServer host, String path, String body) {
        host.createContext(path, exchange -> {
            fetched.merge("http://127.0.0.1:" + host.getAddress().getPort() + path, 1, Integer::sum);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    private static String s256(String verifier) {
        try {
            return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    java.security.MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
