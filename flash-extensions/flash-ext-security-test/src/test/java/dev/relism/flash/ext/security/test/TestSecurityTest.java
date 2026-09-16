package dev.relism.flash.ext.security.test;

import dev.relism.flash.ext.security.SecurityExtension;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class TestSecurityTest {

    static final SecurityExtension security = new SecurityExtension();

    @RegisterExtension
    static final FlashTest app = FlashTest.of(flash -> flash
            .install(security)
            .install(new TestSecurity())
            .get("/me", (req, res) -> SecurityIdentity.current().principal().name(), security.enforce(SecurityPolicy.AUTHENTICATED)));

    @Test
    void aRequestIsAuthenticatedAsTheGivenPrincipal() {
        app.request().with(TestSecurity.as(() -> "alice")).get("/me").expectStatus(200).expectBody("alice");
    }

    @Test
    void anUnknownTokenIsNobody() {
        app.request().header("Authorization", "Test forged").get("/me").expectStatus(401);
    }
}
