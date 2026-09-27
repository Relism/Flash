package dev.relism.flash.ext.security;

import dev.relism.flash.App;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.http.Request;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityTest {

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
        public java.util.List<SecurityScheme> schemes() {
            return java.util.List.of(SecurityScheme.bearer("key", "opaque"));
        }
    };

    static final LoginMethod KEY_LOGIN = new LoginMethod("key", "Key", "/auth/key/login", LoginMethod.Kind.REDIRECT);

    static final SessionRefresher REFRESH = new SessionRefresher() {
        @Override public Class<? extends Principal> type() { return KeyPrincipal.class; }
        @Override public Session refresh(Session expired) {
            return new Session(expired.id(), expired.principal(), Instant.now().plusSeconds(60));
        }
    };

    static final SecurityModule module = new SecurityModule()
            .roles((identity, role, on) -> identity.principal().name().equals(role + "@" + on.get("project")));
    static final Security security = module.security();

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(module, new OpenApiModule("/openapi", "test", "1"))
            .add(AuthenticationMechanism.class, KEY)
            .add(LoginMethod.class, KEY_LOGIN)
            .add(SessionRefresher.class, REFRESH)
            .get("/me", (req, res) -> SecurityIdentity.current().principal().name(), security.enforce(SecurityPolicy.AUTHENTICATED))
            .get("/key-only", (req, res) -> SecurityIdentity.current().principal().name(), security.enforce(SecurityPolicy.AUTHENTICATED,
                    (req, res) -> { throw dev.relism.flash.http.HttpException.unauthorized(); }, java.util.List.of(KEY)))
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

    /** An application's own page may know ways in the list does not, so a configured one always wins. */
    static final SecurityModule paged = new SecurityModule().loginPage("/sign-in");

    @RegisterExtension
    static final FlashTest own = FlashTest.of(() -> App.create()
            .install(paged)
            .add(LoginMethod.class, KEY_LOGIN)
            .get("/me", (req, res) -> "me", paged.security().enforce(SecurityPolicy.AUTHENTICATED)));

    @Test
    void aConfiguredLoginPageWinsOverTheOnlyLoginMethod() {
        own.request().header("Accept", "text/html").get("/me").expectStatus(302).expectHeader("Location", "/sign-in?redirect=%2Fme");
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

    /** A route restricted to one mechanism takes that credential and nothing else, not even a signed-in browser. */
    @Test
    void aRouteRestrictedToAMechanismIgnoresTheSession() {
        String cookie = app.request().post("/login").expectStatus(200).header("Set-Cookie");
        String session = cookie.substring(0, cookie.indexOf(';'));

        app.request().header("Cookie", session).get("/me").expectStatus(200);
        app.request().header("Cookie", session).get("/key-only").expectStatus(401);
        app.request().header("Authorization", "Key alice").get("/key-only").expectStatus(200).expectBody("alice");
    }

    /** The client chooses its Host and any X-Forwarded-* it likes: only a configured origin is the application's own. */
    @Test
    void theOriginIsConfiguredNeverTakenFromForwardedHeaders() {
        String forwarded = app.request().header("X-Forwarded-Proto", "https").header("X-Forwarded-Host", "evil.example")
                .post("/login").expectStatus(200).header("Set-Cookie");
        assertFalse(forwarded.contains("Secure"), forwarded);

        FlashResponse login = configured.request().post("/login").expectStatus(200).expectBody("https://app.example");
        assertTrue(login.header("Set-Cookie").contains("; Secure"));
    }

    static final SecurityModule served = new SecurityModule().origin("https://app.example/");

    @RegisterExtension
    static final FlashTest configured = FlashTest.of(() -> App.create().install(served).post("/login", (req, res) -> {
        served.security().signIn(req, res, new KeyPrincipal("carol", Set.of()));
        return served.security().origin(req);
    }));

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
        FlashTest broken = FlashTest.of(() -> App.create()
                .install(new SecurityModule())
                .scan("dev.relism.flash.ext.security.fixtures"));
        assertThrows(Exception.class, () -> broken.get("/open"));
    }
}
