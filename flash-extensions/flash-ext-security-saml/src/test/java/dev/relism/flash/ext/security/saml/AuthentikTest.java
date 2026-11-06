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
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * authentik as the identity provider, with an EC key: assertions signed with ECDSA in XML, and logout
 * messages signed with ECDSA on the redirect, r and s side by side. It imports this application's
 * metadata, and the application connects it at runtime. Needs Docker, and several minutes for authentik
 * to migrate its database, so it runs only when asked: {@code ./mvnw test -Pinterop -Dinterop.authentik=true}.
 * The ECDSA encodings it proves are covered on every build by {@code SamlTest} with an EC FakeSamlIdp.
 */
@Tag("interop")
@EnabledIfSystemProperty(named = "interop.authentik", matches = "true")
class AuthentikTest {

    static final String ORIGIN = "https://app.test";
    static final String TOKEN = "flash-interop-token";
    static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    static final Network network = Network.newNetwork();

    static GenericContainer<?> postgres, server, worker;
    static String base;
    static Server app;
    static String local;
    static SamlModule saml;

    @BeforeAll
    static void start() throws Exception {
        postgres = new GenericContainer<>("docker.io/library/postgres:16-alpine").withNetwork(network).withNetworkAliases("postgresql")
                .withEnv(Map.of("POSTGRES_PASSWORD", "pw", "POSTGRES_USER", "authentik", "POSTGRES_DB", "authentik"))
                .waitingFor(Wait.forLogMessage(".*ready to accept connections.*\\n", 2));
        postgres.start();
        Map<String, String> env = Map.of("AUTHENTIK_POSTGRESQL__HOST", "postgresql", "AUTHENTIK_POSTGRESQL__USER", "authentik",
                "AUTHENTIK_POSTGRESQL__NAME", "authentik", "AUTHENTIK_POSTGRESQL__PASSWORD", "pw",
                "AUTHENTIK_SECRET_KEY", "flash-interop-secret-key-0123456789abcdef", "AUTHENTIK_BOOTSTRAP_PASSWORD", "akpass",
                "AUTHENTIK_BOOTSTRAP_TOKEN", TOKEN, "AUTHENTIK_BOOTSTRAP_EMAIL", "admin@corp.test",
                "AUTHENTIK_ERROR_REPORTING__ENABLED", "false", "AUTHENTIK_DISABLE_UPDATE_CHECK", "true");
        worker = new GenericContainer<>("ghcr.io/goauthentik/server:2026.8.5").withNetwork(network).withEnv(env).withCommand("worker");
        server = new GenericContainer<>("ghcr.io/goauthentik/server:2026.8.5").withNetwork(network).withEnv(env).withCommand("server")
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/-/health/ready/").forPort(9000).withStartupTimeout(Duration.ofMinutes(8)));
        worker.start();
        server.start();
        base = "http://" + server.getHost() + ":" + server.getMappedPort(9000);
        // The worker applies the bootstrap token and the default flows some time after the server is up.
        for (int i = 0; !ready(); i++) {
            if (i == 90) throw new IllegalStateException("authentik never finished applying its defaults");
            Thread.sleep(2000);
        }

        FakeSamlIdp.Credential sp = FakeSamlIdp.Credential.generate();
        SecurityModule security = new SecurityModule().origin(ORIGIN);
        saml = new SamlModule(SamlKeys.of(sp.privateKeyPem(), sp.certificatePem())).allowLocalProviders();
        app = Server.start(App.create().install(security, saml)
                        .get("/me", (req, res) -> SecurityIdentity.current().principal(SamlPrincipal.class).name(), security.security().enforce(SecurityPolicy.AUTHENTICATED)),
                ServerConfig.builder().host("127.0.0.1").port(0).build());
        local = "http://127.0.0.1:" + app.port();

        // What an administrator does: import the application's metadata, then pick an EC key and Redirect logout.
        String metadata = HTTP.send(HttpRequest.newBuilder(URI.create(local + "/auth/saml/metadata")).build(), HttpResponse.BodyHandlers.ofString()).body();
        String authorization = flow("default-provider-authorization-implicit-consent"), invalidation = flow("default-provider-invalidation-flow");
        HttpResponse<String> imported = importMetadata(metadata, authorization, invalidation);
        assertTrue(imported.statusCode() / 100 == 2, imported.body());
        Map<String, Object> provider = first(api("GET", "/providers/saml/?name=flash", null).body());
        String key = (String) JSONObjectUtils.parse(api("POST", "/crypto/certificatekeypairs/generate/",
                "{\"common_name\":\"flash-ec\",\"validity_days\":365,\"alg\":\"ecdsa\"}").body()).get("pk");
        HttpResponse<String> patched = api("PATCH", "/providers/saml/" + provider.get("pk") + "/", "{\"signing_kp\":\"" + key + "\","
                + "\"signature_algorithm\":\"http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256\",\"digest_algorithm\":\"http://www.w3.org/2001/04/xmlenc#sha256\","
                + "\"sign_assertion\":true,\"sign_response\":true,\"sls_binding\":\"redirect\",\"logout_method\":\"frontchannel_native\","
                + "\"sign_logout_request\":true,\"sign_logout_response\":true}");
        System.out.println("authentik made of the metadata: " + patched.body());
        assertEquals(200, patched.statusCode(), patched.body());
        assertEquals(201, api("POST", "/core/applications/", "{\"name\":\"flash\",\"slug\":\"flash\",\"provider\":" + provider.get("pk") + "}").statusCode());

        saml.saml().register(SamlIdp.of("ak", base + "/application/saml/flash/metadata/"));
    }

    @AfterAll
    static void stop() {
        if (app != null) app.stop().join();
        for (GenericContainer<?> container : new GenericContainer<?>[]{server, worker, postgres}) if (container != null) container.stop();
        network.close();
    }

    /** Signs akadmin in through authentik's flows, as its own login page would, and returns the browser. */
    static Browser signIn() throws Exception {
        Browser browser = new Browser(ORIGIN, local);
        HttpResponse<String> login = browser.follow(browser.get(ORIGIN + "/auth/saml/ak/login?redirect=%2Fme"));
        Map<String, Object> done = execute(browser, login.uri().toString(), Map.of("ak-stage-identification", "{\"uid_field\":\"akadmin\"}",
                "ak-stage-password", "{\"password\":\"akpass\"}"));
        assertEquals("xak-flow-redirect", done.get("component"), done.toString());
        HttpResponse<String> consent = browser.follow(browser.get(URI.create(base).resolve((String) done.get("to")).toString()));
        Map<String, Object> posting = execute(browser, consent.uri().toString(), Map.of());
        assertEquals("ak-stage-autosubmit", posting.get("component"), posting.toString());
        @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) posting.get("attrs");
        Map<String, String> form = new HashMap<>();
        fields.forEach((name, value) -> form.put(name, String.valueOf(value)));
        assertTrue(new String(java.util.Base64.getMimeDecoder().decode(form.get("SAMLResponse")), StandardCharsets.UTF_8).contains("ecdsa-sha256"), "signed with ECDSA");
        HttpResponse<String> signedIn = browser.post((String) posting.get("url"), form);
        assertEquals(303, signedIn.statusCode(), signedIn.body());
        return browser;
    }

    @Test
    void aUserSignsInWithAnAssertionSignedWithEcdsa() throws Exception {
        Browser browser = signIn();
        HttpResponse<String> me = browser.get(ORIGIN + "/me");
        assertEquals(200, me.statusCode(), me.body());
        assertTrue(!me.body().isBlank(), me.body());
    }

    /** authentik answers the logout with a LogoutResponse on a redirect, its ECDSA signature in the query. */
    @Test
    void signingOutComesBackWithARedirectSignedWithEcdsa() throws Exception {
        Browser browser = signIn();
        HttpResponse<String> toAuthentik = browser.post(ORIGIN + "/auth/logout", Map.of());
        assertEquals(303, toAuthentik.statusCode());
        HttpResponse<String> flow = browser.follow(browser.get(toAuthentik.headers().firstValue("Location").orElseThrow()));
        Map<String, Object> loggedOut = execute(browser, flow.uri().toString(), Map.of());
        String back = (String) loggedOut.get("redirect_url");
        assertTrue(back != null && back.startsWith(ORIGIN + "/auth/saml/slo?SAMLResponse=") && back.contains("SigAlg=http%3A%2F%2Fwww.w3.org%2F2001%2F04%2Fxmldsig-more%23ecdsa-sha256"),
                loggedOut.toString());
        HttpResponse<String> done = browser.get(back);
        assertEquals(302, done.statusCode(), done.body());
        assertEquals("/", done.headers().firstValue("Location").orElseThrow());
        assertEquals(401, browser.get(ORIGIN + "/me").statusCode());
    }

    /**
     * Runs an authentik flow through its executor API, answering each stage named in {@code answers}, and
     * returns the first challenge it has no answer for.
     */
    static Map<String, Object> execute(Browser browser, String page, Map<String, String> answers) throws Exception {
        URI uri = URI.create(page);
        String[] path = uri.getPath().split("/");
        String executor = base + "/api/v3/flows/executor/" + path[path.length - 1] + "/?query=" + URLEncoder.encode(uri.getRawQuery(), StandardCharsets.UTF_8);
        Map<String, Object> challenge = parse(browser.json(executor, null));
        for (int stage = 0; stage < 10; stage++) {
            String component = (String) challenge.get("component");
            if (!answers.containsKey(component)) return challenge;
            String answer = answers.get(component);
            challenge = parse(browser.json(executor, "{\"component\":\"" + component + "\"," + answer.substring(1)));
        }
        throw new IllegalStateException("The flow did not end: " + challenge);
    }

    /** Whether the bootstrap token works and the worker has applied every flow a sign-in and a logout go through. */
    static boolean ready() throws Exception {
        for (String slug : List.of("default-authentication-flow", "default-provider-authorization-implicit-consent", "default-provider-invalidation-flow")) {
            HttpResponse<String> flows = api("GET", "/flows/instances/?slug=" + slug, null);
            if (flows.statusCode() != 200 || !flows.body().contains("\"slug\":\"" + slug + "\"")) return false;
        }
        return true;
    }

    static Map<String, Object> parse(HttpResponse<String> response) {
        try {
            return JSONObjectUtils.parse(response.body());
        } catch (java.text.ParseException notJson) {
            throw new IllegalStateException(response.statusCode() + " from " + response.uri() + ":\n" + response.body());
        }
    }

    static String flow(String slug) throws Exception {
        return (String) first(api("GET", "/flows/instances/?slug=" + slug, null).body()).get("pk");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> first(String list) throws Exception {
        return ((List<Map<String, Object>>) JSONObjectUtils.parse(list).get("results")).getFirst();
    }

    static HttpResponse<String> api(String method, String path, String json) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + "/api/v3" + path)).header("Authorization", "Bearer " + TOKEN);
        if (json != null) request.header("Content-Type", "application/json");
        return HTTP.send(request.method(method, json == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(json)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> importMetadata(String metadata, String authorization, String invalidation) throws Exception {
        String boundary = "flash" + System.nanoTime();
        StringBuilder body = new StringBuilder();
        for (String[] field : new String[][]{{"name", "flash"}, {"authorization_flow", authorization}, {"invalidation_flow", invalidation}}) {
            body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"").append(field[0]).append("\"\r\n\r\n").append(field[1]).append("\r\n");
        }
        body.append("--").append(boundary).append("\r\nContent-Disposition: form-data; name=\"file\"; filename=\"metadata.xml\"\r\nContent-Type: application/xml\r\n\r\n")
                .append(metadata).append("\r\n--").append(boundary).append("--\r\n");
        return HTTP.send(HttpRequest.newBuilder(URI.create(base + "/api/v3/providers/saml/import_metadata/")).header("Authorization", "Bearer " + TOKEN)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary).POST(HttpRequest.BodyPublishers.ofString(body.toString())).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
