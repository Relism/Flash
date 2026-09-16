package dev.relism.flash.ext.security;

import dev.relism.flash.ext.openapi.OpenApiExtension;
import dev.relism.flash.models.Request;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertThrows;

class SecurityExtensionTest {

    /** {@code Authorization: Key <name>[ <scope>...]}; {@code Key !} is a presented, invalid credential. */
    record KeyPrincipal(String name, Set<String> scopes) implements Principal {
        @Override public boolean hasScope(String scope) { return scopes.contains(scope); }
    }

    static final AuthenticationMechanism KEY = new AuthenticationMechanism() {
        @Override
        public Principal authenticate(Request req) {
            String header = req.header("Authorization");
            if (header == null || !header.startsWith("Key ")) return null;
            String[] parts = header.substring(4).split(" ");
            if (parts[0].equals("!")) throw new AuthenticationFailedException("Key error=\"invalid_token\"");
            return new KeyPrincipal(parts[0], Set.of(java.util.Arrays.copyOfRange(parts, 1, parts.length)));
        }

        @Override
        public SecurityScheme scheme() {
            return SecurityScheme.bearer("key", "opaque");
        }
    };

    static final SecurityExtension security = new SecurityExtension()
            .roles((identity, role, on) -> identity.principal().name().equals(role + "@" + on.get("project")))
            .mechanism(KEY)
            .loginMethod(new LoginMethod("key", "Key", "/auth/key/login", LoginMethod.Kind.REDIRECT))
            .refresher(KeyPrincipal.class, expired -> new Session(expired.id(), expired.principal(), Instant.now().plusSeconds(60)));

    @RegisterExtension
    static final FlashTest app = FlashTest.of(flash -> flash
            .install(security)
            .install(new OpenApiExtension("/openapi", "test", "1"))
            .get("/me", (req, res) -> SecurityIdentity.current().principal().name(), security.enforce(SecurityPolicy.AUTHENTICATED))
            .post("/login", (req, res) -> {
                security.signIn(req, res, new KeyPrincipal("carol", Set.of()), Instant.now().minusSeconds(1));
                return "signed in";
            })
            .scan("dev.relism.flash.ext.security.fixtures"));

    @Test
    void aValidCredentialIdentifiesTheCaller() {
        app.request().header("Authorization", "Key alice").get("/me").expectStatus(200).expectBody("alice");
    }

    /** Rejected is never anonymous: a browser presenting a bad credential must not be sent to sign in. */
    @Test
    void aRejectedCredentialIs401WithItsOwnChallengeEvenForABrowser() {
        app.request().header("Authorization", "Key !").header("Accept", "text/html").get("/me")
                .expectStatus(401)
                .expectHeader("WWW-Authenticate", "Key error=\"invalid_token\"");
    }

    @Test
    void anApiCallWithoutCredentialsIsChallengedForEveryMechanism() {
        app.get("/me").expectStatus(401).expectHeader("WWW-Authenticate", "Bearer realm=\"key\"");
    }

    @Test
    void aBrowserWithoutCredentialsGoesStraightToTheOnlyLoginMethod() {
        app.request().header("Accept", "text/html").get("/me?tab=keys")
                .expectStatus(302)
                .expectHeader("Location", "/auth/key/login?redirect=%2Fme%3Ftab%3Dkeys");
    }

    @Test
    void permitAllIdentifiesWhoeverAuthenticatesAndToleratesEveryoneElse() {
        app.request().header("Authorization", "Key bob").get("/open").expectBody("bob");
        app.request().header("Authorization", "Key !").get("/open").expectStatus(200).expectBody("anonymous");
        app.get("/open").expectBody("anonymous");
    }

    @Test
    void rolesAreCheckedOnTheResourceThePathNames() {
        app.request().header("Authorization", "Key MANAGER@42").get("/projects/42").expectStatus(200);
        app.request().header("Authorization", "Key MANAGER@42").get("/projects/7").expectStatus(403);
    }

    @Test
    void aMissingScopeIs403WithAnInsufficientScopeChallenge() {
        app.request().header("Authorization", "Key dave write").post("/write").expectStatus(200);
        app.request().header("Authorization", "Key dave").post("/write")
                .expectStatus(403)
                .expectHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\", scope=\"write\"");
    }

    /** The session is signed in already expired, so reaching /me proves the refresher ran. */
    @Test
    void aSessionIsRefreshedWhenExpiredAndEndedBySigningOut() {
        String cookie = app.request().post("/login").expectStatus(200).header("Set-Cookie");
        String session = cookie.substring(0, cookie.indexOf(';'));

        app.request().header("Cookie", session).get("/me").expectStatus(200).expectBody("carol");
        app.request().header("Cookie", session).post("/auth/logout").expectStatus(303).expectHeader("Location", "/");
        app.request().header("Cookie", session).get("/me").expectStatus(401);
    }

    @Test
    void loginMethodsAreListed() {
        app.get("/auth/methods").expectStatus(200).expectBody("[{\"id\":\"key\",\"name\":\"Key\",\"url\":\"/auth/key/login\",\"kind\":\"redirect\"}]");
    }

    @Test
    void openApiDocumentsEachSchemeAndWhatEachOperationRequires() {
        app.get("/openapi.json").expectStatus(200)
                .expectBodyContains("\"securitySchemes\"")
                .expectBodyContains("\"bearerFormat\":\"opaque\"")
                .expectBodyContains("Requires role MANAGER on project")
                .expectBodyContains("Requires scopes write");
    }

    @Test
    void declaringRolesWithoutAResolverFailsTheBoot() {
        FlashTest broken = FlashTest.of(flash -> flash
                .install(new SecurityExtension())
                .scan("dev.relism.flash.ext.security.fixtures"));
        assertThrows(Exception.class, () -> broken.get("/open"));
    }
}
