package dev.relism.flash.ext.security.form;

import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FormLoginExtensionTest {

    static final String HASH = PasswordEncoder.pbkdf2().encode("s3cret");
    static final SecurityExtension security = new SecurityExtension();

    @RegisterExtension
    static final FlashTest app = FlashTest.of(flash -> flash
            .install(security)
            .install(new FormLoginExtension(username -> username.equals("alice")
                    ? new PasswordStore.Account(() -> "alice", HASH) : null))
            .get("/me", (req, res) -> SecurityIdentity.current().principal().name(), security.enforce(SecurityPolicy.AUTHENTICATED)));

    static FlashResponse login(String form, String query) {
        return app.request().header("content-type", "application/x-www-form-urlencoded").body(form)
                .post(FormLoginExtension.LOGIN + query);
    }

    @Test
    void theRightPasswordStartsASessionAndRedirectsToALocalPath() {
        var response = login("username=alice&password=s3cret", "?redirect=%2Fprojects")
                .expectStatus(303).expectHeader("Location", "/projects");
        String cookie = response.header("Set-Cookie");
        app.request().header("Cookie", cookie.substring(0, cookie.indexOf(';'))).get("/me").expectBody("alice");
    }

    @Test
    void aForeignRedirectIsIgnored() {
        login("username=alice&password=s3cret", "?redirect=%2F%2Fevil.example").expectHeader("Location", "/");
    }

    @Test
    void aWrongPasswordAndAnUnknownAccountAreTheSame401() {
        assertNull(login("username=alice&password=nope", "").expectStatus(401).header("Set-Cookie"));
        login("username=mallory&password=s3cret", "").expectStatus(401);
    }

    @Test
    void pbkdf2RoundTripsAndRejectsWhatItDidNotProduce() {
        PasswordEncoder encoder = PasswordEncoder.pbkdf2();
        assertTrue(encoder.matches("s3cret", HASH));
        assertFalse(encoder.matches("s3cret", "plain"));
        assertFalse(encoder.matches("s3cret", null));
    }

    @Test
    void theFormIsListedAsALoginMethod() {
        app.get("/auth/methods").expectBodyContains("\"kind\":\"form\"");
    }
}
