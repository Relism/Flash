package dev.relism.flash.ext.security.oidc;

import dev.relism.flash.ext.security.RolesAllowed;
import dev.relism.flash.ext.security.ScopesAllowed;
import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.ext.security.test.FakeOidcProvider;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OidcExtensionTest {

    static final FakeOidcProvider provider = start();
    static final FakeOidcProvider stranger = start();
    static final SecurityExtension security = new SecurityExtension().roles(ClaimRoles.at("realm_access.roles"));

    @RolesAllowed("admin") static class AdminOnly {}
    @ScopesAllowed("write") static class Writers {}

    @RegisterExtension
    static final FlashTest app = FlashTest.of(flash -> flash
            .install(security)
            .install(new OidcExtension(OidcProvider.of("fake", provider.issuer(), "app", "secret").name("Fake")))
            .get("/me", (req, res) -> {
                OidcPrincipal principal = SecurityIdentity.current().principal(OidcPrincipal.class);
                return principal.name() + " " + principal.email();
            }, security.enforce(SecurityPolicy.AUTHENTICATED))
            .get("/admin", (req, res) -> "admin", security.enforce(SecurityPolicy.of(AdminOnly.class)))
            .get("/write", (req, res) -> "write", security.enforce(SecurityPolicy.of(Writers.class))));

    static FakeOidcProvider start() {
        try {
            return new FakeOidcProvider();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // -- Bearer ---------------------------------------------------------------

    @Test
    void aBearerTokenFromTheProviderAuthenticates() {
        app.request().with(provider.bearer("bob", Map.of("email", "bob@example.test"))).get("/me")
                .expectStatus(200).expectBody("bob bob@example.test");
    }

    @Test
    void aTamperedTokenIsInvalid() {
        String token = provider.token("bob", Map.of());
        app.request().header("Authorization", "Bearer " + token.substring(0, token.length() - 4) + "AAAA").get("/me")
                .expectStatus(401).expectHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    }

    /** RFC 9068: an access token says so in its typ. An ID token is the client's, never a bearer credential. */
    @Test
    void anIdTokenIsNotAnAccessToken() {
        app.request().header("Authorization", "Bearer " + provider.idToken("bob", Map.of("aud", "app"))).get("/me")
                .expectStatus(401).expectHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
    }

    /** Not rejected as invalid: an issuer this application does not know is some other mechanism's business. */
    @Test
    void aTokenFromAnUnknownIssuerIsNotThisMechanisms() {
        app.request().with(stranger.bearer("bob", Map.of())).get("/me")
                .expectStatus(401).expectHeader("WWW-Authenticate", "Bearer realm=\"fake\"");
    }

    @Test
    void rolesAndScopesAreReadFromTheToken() {
        app.request().with(provider.bearer("root", Map.of("realm_access", Map.of("roles", List.of("admin"))))).get("/admin").expectStatus(200);
        app.request().with(provider.bearer("bob", Map.of("realm_access", Map.of("roles", List.of("user"))))).get("/admin").expectStatus(403);
        app.request().with(provider.bearer("bob", Map.of("scope", "read write"))).get("/write").expectStatus(200);
        app.request().with(provider.bearer("bob", Map.of("scope", "read"))).get("/write").expectStatus(403);
    }

    // -- Browser sign-in --------------------------------------------------------

    @Test
    void signingInEndsInASessionThatRefreshesAndSignsOutAtTheProvider() throws Exception {
        provider.signInAs("carol", Map.of("email", "carol@example.test")).expiresIn(0);

        FlashResponse login = app.request().get("/auth/oidc/fake/login?redirect=%2Fme").expectStatus(302);
        String flow = cookie(login.headers().allValues("Set-Cookie"), "flash_oidc");
        HttpResponse<String> approved = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(login.header("Location"))).build(), HttpResponse.BodyHandlers.ofString());
        String callback = URI.create(approved.headers().firstValue("Location").orElseThrow()).getRawPath()
                + "?" + URI.create(approved.headers().firstValue("Location").orElseThrow()).getRawQuery();

        FlashResponse signedIn = app.request().header("Cookie", flow).get(callback).expectStatus(302).expectHeader("Location", "/me");
        String session = cookie(signedIn.headers().allValues("Set-Cookie"), "flash_session");

        // expiresIn(0): the session is already expired, so this answer came through the refresh token.
        app.request().header("Cookie", session).get("/me").expectStatus(200).expectBody("carol carol@example.test");
        String logout = app.request().header("Cookie", session).post("/auth/logout").expectStatus(303).header("Location");
        assertTrue(logout.startsWith(provider.issuer() + "/logout?client_id=app&id_token_hint="), logout);
    }

    @Test
    void aCallbackWithoutItsFlowCookieIsRefused() {
        app.get("/auth/oidc/fake/callback?code=x&state=y").expectStatus(400);
        app.get("/auth/oidc/nobody/login").expectStatus(404);
    }

    @Test
    void theProviderIsListedAndDocumented() {
        app.get("/auth/methods").expectBodyContains("{\"id\":\"fake\",\"name\":\"Fake\",\"url\":\"/auth/oidc/fake/login\",\"kind\":\"redirect\"}");
    }

    @Test
    void aProviderRegisteredAtRuntimeIsTrustedUntilUnregistered() throws Exception {
        OidcExtension extension = new OidcExtension().allowLocalProviders();
        SecurityExtension own = new SecurityExtension();
        FlashTest runtime = FlashTest.of(flash -> flash.install(own).install(extension)
                .get("/me", (req, res) -> SecurityIdentity.current().principal().name(), own.enforce(SecurityPolicy.AUTHENTICATED)));
        try {
            runtime.get("/me").expectStatus(401);
            extension.register(OidcProvider.of("byo", stranger.issuer(), "app", "secret"));
            runtime.request().with(stranger.bearer("dora", Map.of())).get("/me").expectStatus(200).expectBody("dora");
            extension.unregister("byo");
            runtime.request().with(stranger.bearer("dora", Map.of())).get("/me").expectStatus(401);
        } finally {
            runtime.app().stop().join();
        }
    }

    /** Checked before anyone trusts it, and told apart: a bad secret is not a bad redirect URI. */
    @Test
    void aProviderIsVerifiedBeforeItIsTrusted() throws Exception {
        try (FakeOidcProvider customer = new FakeOidcProvider().client("s3cret", "https://app.example/auth/oidc/acme/callback")) {
            OidcExtension extension = new OidcExtension().allowLocalProviders();
            extension.verify(OidcProvider.of("acme", customer.issuer(), "app", "s3cret"), "https://app.example");

            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> extension.verify(OidcProvider.of("acme", customer.issuer(), "app", "wrong"), "https://app.example"))
                    .getMessage().contains("client ID or secret"));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> extension.verify(OidcProvider.of("acme", customer.issuer(), "app", "s3cret"), "https://elsewhere.example"))
                    .getMessage().contains("https://elsewhere.example/auth/oidc/acme/callback"));
            // Verifying fetches URLs a customer chose, so it keeps register's address rules.
            assertThrows(IllegalArgumentException.class,
                    () -> new OidcExtension().verify(OidcProvider.of("acme", customer.issuer(), "app", "s3cret"), "https://app.example"));
        }
    }

    /** A provider registered at runtime, from a customer's input, never replaces one the application configured. */
    @Test
    void aRuntimeProviderCannotTakeAConfiguredId() {
        OidcExtension extension = new OidcExtension(OidcProvider.of("central", provider.issuer(), "app", "secret")).allowLocalProviders();
        assertThrows(IllegalArgumentException.class, () -> extension.register(OidcProvider.of("central", stranger.issuer(), "app", "secret")));
    }

    /** Registering a provider makes the server fetch URLs a customer chose: not on its own network, not in clear text. */
    @Test
    void aRuntimeProviderOnAPrivateAddressIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new OidcExtension().register(OidcProvider.of("evil", stranger.issuer(), "app", "secret")));
    }

    private static String cookie(List<String> setCookies, String name) {
        return setCookies.stream().filter(c -> c.startsWith(name + "=")).map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElseThrow();
    }
}
