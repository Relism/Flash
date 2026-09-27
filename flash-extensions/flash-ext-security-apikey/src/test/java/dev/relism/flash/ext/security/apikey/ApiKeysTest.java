package dev.relism.flash.ext.security.apikey;

import dev.relism.flash.App;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

class ApiKeysTest {

    static final Map<String, ApiKey<String>> KEYS = new ConcurrentHashMap<>();
    static final SecurityModule securityModule = new SecurityModule();
    static final Security security = securityModule.security();
    static final ApiKeys<String> apiKeys = new ApiKeys<>("fk", KEYS::get);

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(securityModule)
            .install(apiKeys)
            .get("/grant", (req, res) -> SecurityIdentity.current().principal(ApiKeyPrincipal.class).grant(),
                    security.enforce(SecurityPolicy.AUTHENTICATED)));

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
}
