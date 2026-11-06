package dev.relism.flash.ext.security.saml;

import com.nimbusds.jose.util.JSONObjectUtils;
import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.ext.security.test.FakeSamlIdp;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keycloak as the identity provider, configured the way an administrator would: by importing this
 * application's metadata. A user signs in through Keycloak's own login form, and logs out from either
 * side. Needs Docker.
 */
@Tag("interop")
class KeycloakTest {

    static final String ORIGIN = "https://app.test";
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    static GenericContainer<?> keycloak;
    static String realm;
    static Server server;
    static String app;

    @BeforeAll
    static void start() throws Exception {
        keycloak = new GenericContainer<>("quay.io/keycloak/keycloak:26.4")
                .withEnv(Map.of("KC_BOOTSTRAP_ADMIN_USERNAME", "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD", "admin",
                        "JAVA_OPTS_KC_HEAP", "-Xms128m -Xmx768m"))
                .withCommand("start-dev")
                .withExposedPorts(8080)
                .waitingFor(Wait.forHttp("/realms/master").forPort(8080).withStartupTimeout(Duration.ofMinutes(4)));
        keycloak.start();
        String base = "http://" + keycloak.getHost() + ":" + keycloak.getMappedPort(8080);
        realm = base + "/realms/flash";
        admin(base, "POST", "/admin/realms", "{\"realm\":\"flash\",\"enabled\":true}");
        admin(base, "POST", "/admin/realms/flash/users", "{\"username\":\"alice\",\"enabled\":true,\"email\":\"alice@corp.test\",\"emailVerified\":true,"
                + "\"firstName\":\"Alice\",\"lastName\":\"Liddell\",\"credentials\":[{\"type\":\"password\",\"value\":\"secret\",\"temporary\":false}]}");

        FakeSamlIdp.Credential sp = FakeSamlIdp.Credential.generate();
        SecurityModule security = new SecurityModule().origin(ORIGIN);
        server = Server.start(App.create()
                        .install(security, new SamlModule(SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()),
                                SamlIdp.of("kc", realm + "/protocol/saml/descriptor").name("Keycloak")))
                        .get("/me", (req, res) -> SecurityIdentity.current().principal(SamlPrincipal.class).name(), security.security().enforce(SecurityPolicy.AUTHENTICATED)),
                ServerConfig.builder().host("127.0.0.1").port(0).build());
        app = "http://127.0.0.1:" + server.port();

        // What an administrator does: hand Keycloak the application's metadata.
        String metadata = HTTP.send(HttpRequest.newBuilder(URI.create(app + "/auth/saml/metadata")).build(), HttpResponse.BodyHandlers.ofString()).body();
        String client = admin(base, "POST", "/admin/realms/flash/client-description-converter", metadata);
        System.out.println("Keycloak made of the metadata: " + client);
        admin(base, "POST", "/admin/realms/flash/clients", client);
    }

    @AfterAll
    static void stop() {
        if (server != null) server.stop().join();
        if (keycloak != null) keycloak.stop();
    }

    /** Signs alice in through Keycloak's login page and returns her browser. */
    static Browser signIn() throws Exception {
        Browser browser = new Browser(ORIGIN, app);
        HttpResponse<String> login = browser.follow(browser.get(ORIGIN + "/auth/saml/kc/login?redirect=%2Fme"));
        assertEquals(200, login.statusCode(), login.body());
        HttpResponse<String> posting = browser.submit(login, Map.of("username", "alice", "password", "secret"));
        assertEquals(200, posting.statusCode(), posting.body());
        assertTrue(posting.body().contains("SAMLResponse"), posting.body());
        HttpResponse<String> signedIn = browser.submit(posting, Map.of());
        assertEquals(303, signedIn.statusCode(), signedIn.body());
        assertEquals("/me", signedIn.headers().firstValue("Location").orElseThrow());
        return browser;
    }

    @Test
    void aUserSignsInThroughKeycloaksOwnForm() throws Exception {
        Browser browser = signIn();
        HttpResponse<String> me = browser.get(ORIGIN + "/me");
        assertEquals(200, me.statusCode(), me.body());
        assertEquals("alice", me.body());
    }

    @Test
    void signingOutEndsKeycloaksSessionTooAndComesBack() throws Exception {
        Browser browser = signIn();
        HttpResponse<String> toKeycloak = browser.post(ORIGIN + "/auth/logout", Map.of());
        assertEquals(303, toKeycloak.statusCode());
        assertTrue(toKeycloak.headers().firstValue("Location").orElseThrow().startsWith(realm + "/protocol/saml?SAMLRequest="), toKeycloak.headers().toString());
        HttpResponse<String> back = browser.get(toKeycloak.headers().firstValue("Location").orElseThrow());
        String slo = back.headers().firstValue("Location").orElse("");
        assertTrue(slo.startsWith(ORIGIN + "/auth/saml/slo?SAMLResponse="), back.statusCode() + " " + slo + "\n" + back.body());
        HttpResponse<String> done = browser.get(slo);
        assertEquals(302, done.statusCode(), done.body());
        assertEquals("/", done.headers().firstValue("Location").orElseThrow());
        assertEquals(401, browser.get(ORIGIN + "/me").statusCode());
        // Keycloak's own session is gone: signing in again shows its form, not an immediate response.
        HttpResponse<String> again = browser.follow(browser.get(ORIGIN + "/auth/saml/kc/login"));
        assertTrue(again.body().contains("kc-form-login"), again.body());
    }

    static String admin(String base, String method, String path, String body) throws Exception {
        String token = (String) JSONObjectUtils.parse(HTTP.send(HttpRequest.newBuilder(URI.create(base + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("grant_type=password&client_id=admin-cli&username=admin&password=admin")).build(),
                HttpResponse.BodyHandlers.ofString()).body()).get("access_token");
        HttpResponse<String> response = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + token)
                .header("Content-Type", body.startsWith("{") ? "application/json" : "text/plain")
                .method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) throw new IllegalStateException(path + " answered " + response.statusCode() + ": " + response.body());
        return response.body();
    }
}
