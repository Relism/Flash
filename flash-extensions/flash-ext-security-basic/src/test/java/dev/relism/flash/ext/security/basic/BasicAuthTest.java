package dev.relism.flash.ext.security.basic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.relism.flash.App;
import dev.relism.flash.ext.openapi.OpenApiModule;
import dev.relism.flash.ext.security.Authenticated;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.form.PasswordEncoder;
import dev.relism.flash.ext.security.form.PasswordStore;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Response;
import dev.relism.flash.testing.FlashTest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** Basic credentials through the security chain, the cache in front of the password hash, and the documented scheme. */
class BasicAuthTest {

    @Authenticated
    public static final class Me extends RequestHandler {
        @Override protected Object handle(Request request, Response response) {
            return SecurityIdentity.current().principal().name();
        }
    }

    /** Counts the hashes it checks: the cost the cache saves. */
    static final AtomicInteger checks = new AtomicInteger();
    static final PasswordEncoder counting = new PasswordEncoder() {
        @Override public String encode(CharSequence password) { return "plain:" + password; }
        @Override public boolean matches(CharSequence password, String encoded) {
            checks.incrementAndGet();
            return ("plain:" + password).equals(encoded);
        }
    };
    static final PasswordStore accounts = name -> name.equals("alice")
            ? new PasswordStore.Account(() -> "alice", "plain:s3cret:with:colons") : null;

    @RegisterExtension
    final FlashTest app = FlashTest.of(() -> App.create()
            .install(new SecurityModule(), new BasicAuth(accounts).passwordEncoder(counting).realm("files"),
                    new OpenApiModule())
            .route(HttpMethod.GET, "/me", Me.class));

    @RegisterExtension
    final FlashTest brief = FlashTest.of(() -> App.create()
            .install(new SecurityModule(), new BasicAuth(accounts).passwordEncoder(counting).cacheFor(Duration.ofMillis(50)))
            .route(HttpMethod.GET, "/me", Me.class));

    @BeforeEach
    void reset() {
        checks.set(0);
    }

    private static String basic(String credentials) {
        return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aVerifiedPairIsHashedOnceAndThenRemembered() {
        for (int i = 0; i < 3; i++)
            app.request().header("Authorization", basic("alice:s3cret:with:colons")).get("/me").expectStatus(200).expectBody("alice");
        assertEquals(1, checks.get());
    }

    @Test
    void aWrongPasswordIsNeverRememberedAndIsChallengedAgain() {
        for (int i = 0; i < 2; i++)
            app.request().header("Authorization", basic("alice:wrong")).get("/me")
                    .expectStatus(401).expectHeader("WWW-Authenticate", "Basic realm=\"files\", charset=\"UTF-8\"");
        app.request().header("Authorization", basic("nobody:wrong")).get("/me").expectStatus(401);
        app.request().header("Authorization", "Basic !!!").get("/me").expectStatus(401);
        assertEquals(3, checks.get(), "an unknown account costs a check too");
    }

    @Test
    void anAnonymousCallIsChallengedForBasic() {
        app.get("/me").expectStatus(401).expectHeader("WWW-Authenticate", "Basic realm=\"files\", charset=\"UTF-8\"");
    }

    @Test
    void anEntryExpires() throws Exception {
        brief.request().header("Authorization", basic("alice:s3cret:with:colons")).get("/me").expectStatus(200);
        Thread.sleep(100);
        brief.request().header("Authorization", basic("alice:s3cret:with:colons")).get("/me").expectStatus(200);
        assertEquals(2, checks.get());
    }

    /** Interop 8.1: security-basic → openapi, through SecurityDocs. */
    @Test
    void theDocumentDeclaresHttpBasicOnProtectedOperations() {
        String json = app.get("/openapi.json").expectStatus(200).body();
        assertTrue(json.contains("\"basic\":{") && json.contains("\"scheme\":\"basic\""), json);
        assertTrue(json.contains("\"security\":[{\"basic\":[]}]"), json);
    }
}
