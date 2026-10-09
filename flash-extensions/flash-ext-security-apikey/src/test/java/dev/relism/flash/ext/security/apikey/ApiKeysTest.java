package dev.relism.flash.ext.security.apikey;

import dev.relism.flash.App;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ApiKeysTest {

    static final Map<String, ApiKey<String>> KEYS = new ConcurrentHashMap<>();
    static final SecurityModule securityModule = new SecurityModule()
            .roles((identity, role, on) -> role.equals(identity.principal(ApiKeyPrincipal.class).grant()));
    static final Security security = securityModule.security();
    static final ApiKeys<String> apiKeys = new ApiKeys<>("fk", KEYS::get);

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(securityModule)
            .install(apiKeys)
            .get("/grant", (req, res) -> SecurityIdentity.current().principal(ApiKeyPrincipal.class).grant(),
                    security.enforce(SecurityPolicy.AUTHENTICATED))
            .get("/stream", new dev.relism.flash.sse.SseHandler() {
                @Override protected void handle(dev.relism.flash.http.Request request, dev.relism.flash.sse.SseStream stream) throws Exception {
                    stream.send(String.valueOf(SecurityIdentity.current().principal(ApiKeyPrincipal.class).grant()));
                }
            }, security.enforce(SecurityPolicy.AUTHENTICATED))
            .get("/admin", (req, res) -> "admin", security.enforce(SecurityPolicy.roles("admin")))
            .get("/traced", (req, res) -> "ok", (exchange, next) -> {
                try {
                    next.handle(exchange);
                } catch (HttpException refused) {
                    throw refused.header("X-Trace", "1");
                }
            }, security.enforce(SecurityPolicy.AUTHENTICATED)));

    static String issue(String grant, Instant expiresAt, Instant revokedAt) {
        GeneratedApiKey key = apiKeys.generate();
        KEYS.put(key.id(), new ApiKey<>(key.id(), key.secretHash(), grant, expiresAt, revokedAt));
        return "Bearer " + key.token();
    }

    @Test
    void anIssuedKeyAuthenticatesWithItsGrant() {
        app.request().header("Authorization", issue("project-42", null, null)).get("/grant").expectStatus(200).expectBody("project-42");
    }

    @Test
    void aWrongSecretAnExpiredKeyAndARevokedKeyAreRejectedAsInvalid() {
        String valid = issue("x", null, null);
        for (String token : new String[]{
                valid.substring(0, valid.length() - 1) + (valid.endsWith("A") ? "B" : "A"),
                issue("x", Instant.now().minusSeconds(1), null),
                issue("x", null, Instant.now()),
                "Bearer fk_no-secret-here"}) {
            app.request().header("Authorization", token).get("/grant")
                    .expectStatus(401).expectHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
        }
    }

    /** Another application's bearer token is not a key of ours: it is left to other mechanisms, not rejected. */
    @Test
    void aForeignBearerTokenIsNotThisMechanisms() {
        app.request().header("Authorization", "Bearer eyJhbGciOi.payload.signature").get("/grant")
                .expectStatus(401).expectHeader("WWW-Authenticate", "Bearer realm=\"apiKey\"");
    }

    @Test
    void aRejectionIsNotSharedBetweenRequests() {
        for (int i = 0; i < 3; i++) {
            var response = app.request().header("Authorization", "Bearer fk_no-secret-here").get("/traced").expectStatus(401);
            assertEquals(1, response.headers().allValues("X-Trace").size());
        }
    }

    @Test
    void aDuplexHandlerSeesTheCaller() {
        app.request().header("Authorization", issue("project-7", null, null)).get("/stream").expectStatus(200).expectBody("data: project-7\n\n");
    }

    @Test
    void aLambdaRouteCanRequireARole() {
        app.request().header("Authorization", issue("admin", null, null)).get("/admin").expectStatus(200);
        app.request().header("Authorization", issue("x", null, null)).get("/admin").expectStatus(403);
    }
}
