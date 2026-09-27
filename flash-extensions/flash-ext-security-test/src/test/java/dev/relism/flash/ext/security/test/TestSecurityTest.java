package dev.relism.flash.ext.security.test;

import dev.relism.flash.App;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

class TestSecurityTest {

    static final SecurityModule securityModule = new SecurityModule();
    static final Security security = securityModule.security();

    static final TestSecurity users = new TestSecurity();

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(securityModule)
            .install(users)
            .get("/me", (req, res) -> SecurityIdentity.current().principal().name(), security.enforce(SecurityPolicy.AUTHENTICATED)));

    @Test
    void aRequestIsAuthenticatedAsTheGivenPrincipal() {
        app.request().with(users.as(() -> "alice")).get("/me").expectStatus(200).expectBody("alice");
    }

    @Test
    void anUnknownTokenIsNobody() {
        app.request().header("Authorization", "Test forged").get("/me").expectStatus(401);
    }
}
