package dev.relism.flash.ext.security.saml;

import dev.relism.flash.App;
import dev.relism.flash.ext.security.ClaimRoles;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.SecurityModule;
import dev.relism.flash.ext.security.SecurityPolicy;
import dev.relism.flash.ext.security.test.FakeSamlIdp;
import dev.relism.flash.testing.FlashResponse;
import dev.relism.flash.testing.FlashTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPrivateCrtKeySpec;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SamlTest {

    static final String ORIGIN = "https://app.test";
    static final String ACS = ORIGIN + "/auth/saml/acs";
    static final String SLO = ORIGIN + "/auth/saml/slo";
    static final String ENTITY = ORIGIN + "/auth/saml/metadata";
    static final String GCM = "http://www.w3.org/2009/xmlenc11#aes256-gcm";
    static final String CBC = "http://www.w3.org/2001/04/xmlenc#aes128-cbc";

    static final FakeSamlIdp.Credential sp = FakeSamlIdp.Credential.generate();
    static final FakeSamlIdp.Credential retired = FakeSamlIdp.Credential.generate();
    static final FakeSamlIdp idp = start(FakeSamlIdp.Credential.generate());
    static final SecurityModule securityModule = new SecurityModule().origin(ORIGIN).roles(ClaimRoles.at("groups"));
    static final Security security = securityModule.security();
    static final SamlModule samlModule = new SamlModule(SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()).and(retired.privateKeyPem(), retired.certificatePem()),
            SamlIdp.of("corp", idp.metadataUrl()).name("Corp"))
            .info("Test app", "https://app.test/privacy", "ops@app.test")
            .allowLocalProviders();

    @RegisterExtension
    static final FlashTest app = FlashTest.of(() -> App.create()
            .install(securityModule, samlModule)
            .get("/me", (req, res) -> {
                SamlPrincipal principal = SecurityIdentity.current().principal(SamlPrincipal.class);
                return principal.name() + " " + principal.attribute("mail");
            }, security.enforce(SecurityPolicy.AUTHENTICATED))
            .get("/admin", (req, res) -> "admin", security.enforce(SecurityPolicy.roles("admin"))));

    static FakeSamlIdp start(FakeSamlIdp.Credential credential) {
        try {
            return new FakeSamlIdp(credential);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @BeforeEach
    void reset() throws Exception {
        idp.signInAs("alice", Map.of("mail", List.of("alice@corp.test"), "groups", List.of("staff"))).sign(true, true)
                .encryptTo(null, null).rewrite(xml -> xml);
    }

    /** The login redirect and the flow cookie it set. */
    record Login(String location, String cookie) {}

    static Login login(String provider) {
        FlashResponse login = app.get("/auth/saml/" + provider + "/login?redirect=%2Fme").expectStatus(302);
        return new Login(login.header("Location"), cookie(login, "flash_saml"));
    }

    static FlashResponse post(String samlResponse, String cookie) {
        var request = app.request().header("Content-Type", "application/x-www-form-urlencoded");
        if (cookie != null) request.header("Cookie", cookie);
        return request.body("SAMLResponse=" + URLEncoder.encode(samlResponse, StandardCharsets.UTF_8)).post("/auth/saml/acs");
    }

    /** Signs in through {@code provider} and returns the session cookie. */
    static String session(FakeSamlIdp provider, String id) throws Exception {
        Login login = login(id);
        FlashResponse signedIn = post(provider.respond(login.location()), login.cookie()).expectStatus(303).expectHeader("Location", "/me");
        return cookie(signedIn, "flash_session");
    }

    static String cookie(FlashResponse response, String name) {
        return response.headers().allValues("Set-Cookie").stream().filter(c -> c.startsWith(name + "=")).map(c -> c.substring(0, c.indexOf(';'))).findFirst().orElseThrow();
    }

    static String xml(String samlResponse) {
        return new String(Base64.getDecoder().decode(samlResponse), StandardCharsets.UTF_8);
    }

    static String base64(String xml) {
        return Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8));
    }

    // -- Sign-in -------------------------------------------------------------------

    @Test
    void signingInEndsInASessionWithTheAttributes() throws Exception {
        Login login = login("corp");
        assertTrue(login.location().startsWith(idp.metadataUrl().replace("/metadata", "/sso?SAMLRequest=")), login.location());
        assertTrue(login.location().contains("&SigAlg=") && login.location().contains("&Signature="), "requests are signed");
        String request = FakeSamlIdp.redirected(login.location(), "SAMLRequest");
        assertTrue(request.contains("AssertionConsumerServiceURL=\"" + ACS + "\"") && !request.contains("NameIDPolicy"), request);
        assertTrue(login.cookie().startsWith("flash_saml="));

        String session = cookie(post(idp.respond(login.location()), login.cookie()).expectStatus(303).expectHeader("Location", "/me"), "flash_session");
        app.request().header("Cookie", session).get("/me").expectStatus(200).expectBody("alice alice@corp.test");
    }

    @Test
    void theFlowCookieCrossesTheProvidersPostOnlyOverHttps() {
        String cookie = app.get("/auth/saml/corp/login").headers().allValues("Set-Cookie").getFirst();
        assertTrue(cookie.contains("SameSite=None; Secure") && cookie.contains("Path=/auth/saml") && cookie.contains("HttpOnly"), cookie);
    }

    @Test
    void eitherSignatureIsEnoughButOneIsRequired() throws Exception {
        idp.sign(false, true);
        session(idp, "corp");
        idp.sign(true, false);
        session(idp, "corp");
        idp.sign(false, false);
        Login login = login("corp");
        post(idp.respond(login.location()), login.cookie()).expectStatus(400);
    }

    @Test
    void anEncryptedAssertionIsDecryptedWithGcmOrCbcAndWithARetiredKey() throws Exception {
        idp.encryptTo(sp.certificatePem(), GCM);
        session(idp, "corp");
        idp.encryptTo(sp.certificatePem(), CBC);
        session(idp, "corp");
        idp.encryptTo(retired.certificatePem(), GCM);
        session(idp, "corp");
        idp.encryptTo(FakeSamlIdp.Credential.generate().certificatePem(), GCM);
        Login login = login("corp");
        post(idp.respond(login.location()), login.cookie()).expectStatus(400);
    }

    /** A browser that altered its own flow cookie is sent to the root, not answered with an error after its session began. */
    @Test
    void anAlteredReturnPathFallsBackToTheRoot() throws Exception {
        Login login = login("corp");
        String altered = login.cookie().substring(0, login.cookie().indexOf('.') + 1) + "!!!";
        post(idp.respond(login.location()), altered).expectStatus(303).expectHeader("Location", "/");
    }

    @Test
    void aResponseIsConsumedOnce() throws Exception {
        Login login = login("corp");
        String response = idp.respond(login.location());
        post(response, login.cookie()).expectStatus(303);
        post(response, login.cookie()).expectStatus(400);
    }

    /** Someone else's solicited response, posted through this browser, answers no request of it: login CSRF. */
    @Test
    void aResponseNeedsTheBrowserThatAskedForIt() throws Exception {
        Login mine = login("corp");
        Login theirs = login("corp");
        post(idp.respond(theirs.location()), mine.cookie()).expectStatus(400);
        post(idp.respond(theirs.location()), null).expectStatus(400);
    }

    @Test
    void anUnsolicitedResponseIsRefusedUnlessTheProviderIsAllowedOne() throws Exception {
        post(idp.unsolicited(ACS, ENTITY), null).expectStatus(400);

        FakeSamlIdp portal = start(FakeSamlIdp.Credential.generate());
        samlModule.saml().register(SamlIdp.of("portal", portal.metadataUrl()).unsolicited());
        try {
            var request = app.request().header("Content-Type", "application/x-www-form-urlencoded");
            request.body("SAMLResponse=" + URLEncoder.encode(portal.unsolicited(ACS, ENTITY), StandardCharsets.UTF_8) + "&RelayState=%2Fme")
                    .post("/auth/saml/acs").expectStatus(303).expectHeader("Location", "/me");
            request.body("SAMLResponse=" + URLEncoder.encode(portal.unsolicited(ACS, ENTITY), StandardCharsets.UTF_8) + "&RelayState=https%3A%2F%2Fevil.test")
                    .post("/auth/saml/acs").expectStatus(303).expectHeader("Location", "/");
        } finally {
            samlModule.saml().unregister("portal");
            portal.close();
        }
    }

    /** The path to return to ends up in a Location header: a line break there would be a header of its own. */
    @Test
    void theReturnPathCannotInjectAHeaderOrLeaveTheOrigin() throws Exception {
        FlashResponse login = app.get("/auth/saml/corp/login?redirect=%2F%0D%0ASet-Cookie:%20flash_session=forged").expectStatus(302);
        FlashResponse signedIn = post(idp.respond(login.header("Location")), cookie(login, "flash_saml")).expectStatus(303)
                .expectHeader("Location", "/%0D%0ASet-Cookie:%20flash_session=forged");
        assertFalse(signedIn.headers().allValues("Set-Cookie").stream().anyMatch(c -> c.contains("forged")));
        login = app.get("/auth/saml/corp/login?redirect=%2F%09%2Fevil.test").expectStatus(302);
        post(idp.respond(login.header("Location")), cookie(login, "flash_saml")).expectStatus(303).expectHeader("Location", "/%09/evil.test");
    }

    // -- What a signature does not cover ---------------------------------------------

    @Test
    void aSignedAssertionCannotBeEdited() throws Exception {
        idp.sign(false, true);
        Login login = login("corp");
        post(base64(xml(idp.respond(login.location())).replace(">alice<", ">admin<")), login.cookie()).expectStatus(400);
    }

    /** XSW: the signed assertion is moved where nobody reads it, an unsigned one takes its place. */
    @Test
    void aWrappedAssertionIsRefused() throws Exception {
        idp.sign(false, true);
        Login login = login("corp");
        String signed = xml(idp.respond(login.location()));
        int start = signed.indexOf("<saml:Assertion"), end = signed.indexOf("</saml:Assertion>") + "</saml:Assertion>".length();
        String assertion = signed.substring(start, end);
        String forged = assertion.replaceFirst("ID=\"[^\"]+\"", "ID=\"_forged\"").replaceAll("(?s)<ds:Signature.*</ds:Signature>", "").replace(">alice<", ">admin<");
        post(base64(signed.substring(0, start) + forged + "<samlp:Extensions>" + assertion + "</samlp:Extensions>" + signed.substring(end)), login.cookie()).expectStatus(400);
        post(base64(signed.substring(0, end) + forged + signed.substring(end)), login.cookie()).expectStatus(400);
    }

    /** Canonicalization drops comments, so a signature holds over {@code bob@corp.test<!---->.evil}: the whole text is the name. */
    @Test
    void aCommentDoesNotCutTheName() throws Exception {
        idp.signInAs("bob@corp.test.evil", Map.of());
        Login login = login("corp");
        String commented = xml(idp.respond(login.location())).replace(">bob@corp.test.evil<", ">bob@corp.test<!---->.evil<");
        String session = cookie(post(base64(commented), login.cookie()).expectStatus(303), "flash_session");
        app.request().header("Cookie", session).get("/me").expectStatus(200).expectBody("bob@corp.test.evil null");
    }

    @Test
    void aDocumentTypeDeclarationIsRefused() throws Exception {
        Login login = login("corp");
        String xml = xml(idp.respond(login.location())).replaceFirst("^<\\?xml[^>]*>", "");
        post(base64("<!DOCTYPE r [<!ENTITY x \"alice\">]>" + xml), login.cookie()).expectStatus(400);
    }

    @Test
    void aSignedAssertionForAnotherAudienceRecipientRequestOrTimeIsRefused() throws Exception {
        for (var rewrite : List.<java.util.function.UnaryOperator<String>>of(
                xml -> xml.replace("<saml:Audience>" + ENTITY, "<saml:Audience>https://other.test"),
                xml -> xml.replace("Recipient=\"" + ACS, "Recipient=\"https://other.test/acs"),
                xml -> xml.replaceAll("NotOnOrAfter=\"[^\"]+\"", "NotOnOrAfter=\"2020-01-01T00:00:00Z\""),
                xml -> xml.replaceAll("NotBefore=\"[^\"]+\"", "NotBefore=\"" + Instant.now().plusSeconds(3600) + "\""),
                xml -> xml.replace("cm:bearer", "cm:holder-of-key"),
                xml -> xml.replaceFirst("(<saml:SubjectConfirmationData NotOnOrAfter=\")[^\"]+", "$1" + Instant.now().plusSeconds(7200)),
                xml -> xml.replace("</saml:Conditions>", "<saml:Condition/></saml:Conditions>"),
                xml -> xml.replace("Destination=\"" + ACS, "Destination=\"https://other.test/acs"),
                xml -> xml.replaceFirst("(Destination=\"[^\"]+\") InResponseTo=\"[^\"]+\"", "$1 InResponseTo=\"_other\""),
                xml -> xml.replaceFirst("(Recipient=\"[^\"]+\") InResponseTo=\"[^\"]+\"", "$1 InResponseTo=\"_other\""))) {
            idp.rewrite(rewrite);
            Login login = login("corp");
            post(idp.respond(login.location()), login.cookie()).expectStatus(400);
        }
    }

    @Test
    void aProviderErrorIsShownAsItIs() throws Exception {
        idp.rewrite(xml -> xml.replace("status:Success\"/>", "status:Responder\"><samlp:StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:AuthnFailed\"/></samlp:StatusCode>"));
        Login login = login("corp");
        post(idp.respond(login.location()), login.cookie()).expectStatus(400).expectBodyContains("status:AuthnFailed");
        idp.rewrite(xml -> xml.replace("urn:oasis:names:tc:SAML:2.0:status:Success", "Your account is locked, call +1 555"));
        login = login("corp");
        post(idp.respond(login.location()), login.cookie()).expectStatus(400).expectBody("{\"error\":\"Sign-in could not be completed; start again\",\"status\":400}");
    }

    @Test
    void aResponseFromAKeyNotInTheMetadataIsRefused() throws Exception {
        FakeSamlIdp impostor = start(FakeSamlIdp.Credential.generate());
        try {
            impostor.rewrite(xml -> xml.replace(impostor.entityId(), idp.entityId()));
            Login login = login("corp");
            post(impostor.respond(login.location()), login.cookie()).expectStatus(400);
        } finally {
            impostor.close();
        }
    }

    @Test
    void anAlgorithmLeftOutOfTheAllowedOnesIsRefused() throws Exception {
        Login login = login("corp");
        org.w3c.dom.Element response = Xml.parse(Base64.getDecoder().decode(idp.respond(login.location()))).getDocumentElement();
        var key = CertificateFactory.getInstance("X.509").generateCertificate(
                new ByteArrayInputStream(idp.credential().certificatePem().getBytes(StandardCharsets.US_ASCII))).getPublicKey();
        assertTrue(Xml.verify(response, List.of(key), Saml.ALGORITHMS));
        assertThrows(IllegalArgumentException.class, () -> Xml.verify(response, List.of(key), java.util.Set.of(Xml.SHA256)));
    }

    // -- Identity ------------------------------------------------------------------

    @Test
    void rolesAreReadFromAttributes() throws Exception {
        idp.signInAs("root", Map.of("groups", List.of("staff", "admin")));
        app.request().header("Cookie", session(idp, "corp")).get("/admin").expectStatus(200);
        idp.signInAs("bob", Map.of("groups", List.of("staff")));
        app.request().header("Cookie", session(idp, "corp")).get("/admin").expectStatus(403);
    }

    @Test
    void aPairwiseIdIsTheNameBeforeTheNameId() throws Exception {
        idp.signInAs("transient-123", Map.of("urn:oasis:names:tc:SAML:attribute:pairwise-id", List.of("x7f@corp.test")));
        app.request().header("Cookie", session(idp, "corp")).get("/me").expectStatus(200).expectBody("x7f@corp.test null");
        idp.signInAs("transient-123", Map.of("urn:oasis:names:tc:SAML:attribute:pairwise-id", List.of("x7f@")));
        Login login = login("corp");
        post(idp.respond(login.location()), login.cookie()).expectStatus(400);
    }

    @Test
    void anIdentifiersScopeMustBeOneTheMetadataDeclaresLiterally() throws Exception {
        String scopes = "<md:Extensions><shibmd:Scope xmlns:shibmd=\"urn:mace:shibboleth:metadata:1.0\" regexp=\"false\">corp.test</shibmd:Scope>"
                + "<shibmd:Scope xmlns:shibmd=\"urn:mace:shibboleth:metadata:1.0\" regexp=\"true\">.*</shibmd:Scope></md:Extensions>";
        String regexpOnly = "<md:Extensions><shibmd:Scope xmlns:shibmd=\"urn:mace:shibboleth:metadata:1.0\" regexp=\"true\">.*</shibmd:Scope></md:Extensions>";
        for (String extensions : List.of(scopes, regexpOnly)) {
            FakeSamlIdp scoped = start(idp.credential());
            samlModule.saml().register(SamlIdp.fromXml("scoped", scoped.metadata().replaceFirst("(<md:IDPSSODescriptor[^>]*>)", "$1" + extensions)));
            try {
                scoped.signInAs("n", Map.of("urn:oasis:names:tc:SAML:attribute:subject-id", List.of("x@corp.test")));
                Login login = login("scoped");
                post(scoped.respond(login.location()), login.cookie()).expectStatus(extensions == scopes ? 303 : 400);
                scoped.signInAs("n", Map.of("urn:oasis:names:tc:SAML:attribute:subject-id", List.of("x@evil.test")));
                login = login("scoped");
                post(scoped.respond(login.location()), login.cookie()).expectStatus(400);
            } finally {
                samlModule.saml().unregister("scoped");
                scoped.close();
            }
        }
    }

    @Test
    void anAuthnContextAskedForIsRequired() throws Exception {
        FakeSamlIdp strict = start(idp.credential());
        samlModule.saml().register(SamlIdp.of("strict", strict.metadataUrl()).authnContext("urn:oasis:names:tc:SAML:2.0:ac:classes:X509"));
        try {
            Login login = login("strict");
            assertTrue(FakeSamlIdp.redirected(login.location(), "SAMLRequest").contains("Comparison=\"exact\""));
            post(strict.respond(login.location()), login.cookie()).expectStatus(400);
        } finally {
            samlModule.saml().unregister("strict");
            strict.close();
        }
    }

    // -- Logout --------------------------------------------------------------------

    @Test
    void signingOutEndsTheSessionThenTheProvidersOne() throws Exception {
        String session = session(idp, "corp");
        String location = app.request().header("Cookie", session).post("/auth/logout").expectStatus(303).header("Location");
        String request = FakeSamlIdp.redirected(location, "SAMLRequest");
        assertTrue(location.startsWith(idp.metadataUrl().replace("/metadata", "/slo?SAMLRequest=")) && location.contains("&Signature="), location);
        assertTrue(request.contains(">alice</saml:NameID>") && request.contains("SessionIndex"), request);
        app.request().header("Cookie", session).get("/me").expectStatus(401);

        String back = idp.logoutResponse(location, SLO);
        app.get(back.substring(ORIGIN.length())).expectStatus(302).expectHeader("Location", "/");

        idp.rewrite(xml -> xml.replace("status:Success", "status:Responder"));
        app.get(idp.logoutResponse(location, SLO).substring(ORIGIN.length())).expectStatus(400)
                .expectBodyContains("The identity provider did not complete the logout");
    }

    @Test
    void theProviderEndsTheSessionOfTheUserItNames() throws Exception {
        String session = session(idp, "corp");
        app.request().header("Cookie", session).get(idp.logoutRequest(SLO, "bob").substring(ORIGIN.length())).expectStatus(302);
        app.request().header("Cookie", session).get("/me").expectStatus(200);

        String reply = app.request().header("Cookie", session).get(idp.logoutRequest(SLO, "alice").substring(ORIGIN.length())).expectStatus(302).header("Location");
        assertTrue(reply.contains("SAMLResponse=") && reply.contains("RelayState=relay") && reply.contains("&Signature="), reply);
        assertTrue(FakeSamlIdp.redirected(reply, "SAMLResponse").contains("status:Success"));
        app.request().header("Cookie", session).get("/me").expectStatus(401);
    }

    @Test
    void anUnsignedTamperedOrStaleLogoutIsRefused() throws Exception {
        String signed = idp.logoutRequest(SLO, "alice").substring(ORIGIN.length());
        app.get(signed.substring(0, signed.indexOf("&SigAlg="))).expectStatus(400);
        app.get(signed.replace("RelayState=relay", "RelayState=other")).expectStatus(400);
        idp.rewrite(xml -> xml.replaceFirst("IssueInstant=\"[^\"]+\"", "IssueInstant=\"" + Instant.now().minusSeconds(3600).truncatedTo(ChronoUnit.SECONDS) + "\""));
        app.get(idp.logoutRequest(SLO, "alice").substring(ORIGIN.length())).expectStatus(400);
        idp.rewrite(xml -> xml.replaceFirst(" ID=\"[^\"]+\"", ""));
        app.get(idp.logoutRequest(SLO, "alice").substring(ORIGIN.length())).expectStatus(400);
    }

    /** An id unregistered and reused for another provider must not be sent the NameID of a user the first one signed in. */
    @Test
    void loggingOutGoesToTheProviderThatSignedTheUserIn() throws Exception {
        FakeSamlIdp first = start(idp.credential()), second = start(idp.credential());
        try {
            samlModule.saml().register(SamlIdp.of("reused", first.metadataUrl()));
            String session = session(first, "reused");
            samlModule.saml().unregister("reused");
            samlModule.saml().register(SamlIdp.of("reused", second.metadataUrl()));
            app.request().header("Cookie", session).post("/auth/logout").expectStatus(303).expectHeader("Location", "/");
        } finally {
            samlModule.saml().unregister("reused");
            first.close();
            second.close();
        }
    }

    /** Metadata is fetched again before its validUntil, not after: past it, the provider would be refused until then. */
    @Test
    void metadataIsRefreshedBeforeItExpires() throws Exception {
        Instant validUntil = Instant.now().plusSeconds(1800).truncatedTo(ChronoUnit.SECONDS);
        IdentityProvider read = new IdentityProvider(SamlIdp.fromXml("soon", idp.signedMetadata(validUntil)), false, Saml.ALGORITHMS);
        assertEquals(validUntil.minusSeconds(600), read.refreshAt);
    }

    // -- Metadata and providers ------------------------------------------------------

    @Test
    void theApplicationsMetadataIsWhatAProviderImports() {
        String metadata = app.get("/auth/saml/metadata").expectStatus(200).expectHeader("Content-Type", "application/samlmetadata+xml").body();
        assertTrue(metadata.contains("entityID=\"" + ENTITY + "\""), metadata);
        assertTrue(metadata.contains("Location=\"" + ACS + "\" index=\"0\" isDefault=\"true\""), metadata);
        assertTrue(metadata.contains("<md:KeyDescriptor use=\"encryption\">") && metadata.contains("aes256-gcm"), metadata);
        assertTrue(metadata.contains("Test app") && metadata.contains("mailto:ops@app.test"), metadata);
        assertTrue(metadata.contains("<alg:SigningMethod xmlns:alg=\"urn:oasis:names:tc:SAML:metadata:algsupport\" Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256\"/>")
                && metadata.contains("Algorithm=\"http://www.w3.org/2001/04/xmldsig-more#sha384\""), metadata);
        assertFalse(metadata.contains(retired.certificatePem().replaceAll("-----[A-Z ]+-----|\\s", "").substring(300, 360)), "a retired key only decrypts");
    }

    @Test
    void theProviderIsListedAndReachableByEntityId() {
        app.get("/auth/methods").expectBodyContains("{\"id\":\"corp\",\"name\":\"Corp\",\"url\":\"/auth/saml/corp/login\",\"kind\":\"redirect\"}");
        String location = app.get("/auth/saml/login?entityID=" + URLEncoder.encode(idp.entityId(), StandardCharsets.UTF_8)).expectStatus(302).header("Location");
        assertTrue(location.contains("SAMLRequest="), location);
        app.get("/auth/saml/login?entityID=https%3A%2F%2Fnobody.test").expectStatus(404);
        app.get("/auth/saml/nobody/login").expectStatus(404);
    }

    @Test
    void aProviderWithOnlyPostIsSentAPostedSignedRequest() throws Exception {
        FakeSamlIdp posting = start(idp.credential());
        String sso = posting.metadataUrl().replace("/metadata", "/sso");
        samlModule.saml().register(SamlIdp.fromXml("posted", posting.metadata().replace("bindings:HTTP-Redirect\" Location=\"" + sso, "bindings:HTTP-POST\" Location=\"" + sso)));
        try {
            FlashResponse posted = app.get("/auth/saml/posted/login").expectStatus(200);
            assertTrue(posted.header("Content-Type").startsWith("text/html"), posted.header("Content-Type"));
            String page = posted.body();
            byte[] request = Base64.getDecoder().decode(page.replaceFirst("(?s).*name=\"SAMLRequest\" value=\"([^\"]+)\".*", "$1"));
            var spKey = SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()).certificate.getPublicKey();
            assertTrue(Xml.verify(Xml.parse(request).getDocumentElement(), List.of(spKey), Saml.ALGORITHMS), "the posted request is signed with the application's key");
        } finally {
            samlModule.saml().unregister("posted");
            posting.close();
        }
    }

    @Test
    void signedMetadataIsTrustedForItsSignatureAndUntilItsValidity() throws Exception {
        String signer = idp.credential().certificatePem();
        assertThrows(IllegalArgumentException.class, () -> samlModule.saml().register(SamlIdp.fromXml("twin", idp.metadata())), "corp's entity ID");
        FakeSamlIdp federated = start(idp.credential());
        samlModule.saml().register(SamlIdp.fromXml("federated", federated.signedMetadata(Instant.now().plusSeconds(3600))).metadataSigner(signer));
        samlModule.saml().unregister("federated");
        federated.close();
        assertThrows(IllegalArgumentException.class, () -> samlModule.saml().register(SamlIdp.fromXml("f", idp.signedMetadata(null)).metadataSigner(signer)));
        assertThrows(IllegalArgumentException.class, () -> samlModule.saml().register(SamlIdp.fromXml("f", idp.signedMetadata(Instant.now().minusSeconds(1)))));
        assertThrows(IllegalArgumentException.class, () -> samlModule.saml().register(SamlIdp.fromXml("f",
                idp.signedMetadata(Instant.now().plusSeconds(3600)).replace("/sso\"", "/evil\"")).metadataSigner(signer)));
        assertThrows(IllegalArgumentException.class, () -> samlModule.saml().register(SamlIdp.fromXml("f",
                idp.signedMetadata(Instant.now().plusSeconds(3600))).metadataSigner(FakeSamlIdp.Credential.generate().certificatePem())));
    }

    /** An endpoint the browser is sent to, by a Location header or a form this origin serves, is a web address and nothing else. */
    @Test
    void anEndpointThatIsNotAWebAddressIsRefused() {
        String sso = idp.metadataUrl().replace("/metadata", "/sso");
        for (String endpoint : List.of("javascript:alert(1)", sso + "&#13;&#10;Set-Cookie: x=1", "/relative")) {
            assertThrows(IllegalArgumentException.class, () -> samlModule.saml().register(SamlIdp.fromXml("bad",
                    idp.metadata().replaceFirst("bindings:HTTP-Redirect\" Location=\"[^\"]+/sso\"", "bindings:HTTP-POST\" Location=\"" + endpoint + "\""))), endpoint);
        }
    }

    /** U+010D U+010A are a valid URL's characters, and a line break once a header keeps their low bytes: they go out percent-encoded. */
    @Test
    void anEndpointGoesOutInAscii() throws Exception {
        FakeSamlIdp unicode = start(idp.credential());
        String sso = unicode.metadataUrl().replace("/metadata", "/sso");
        samlModule.saml().register(SamlIdp.fromXml("unicode", unicode.metadata().replace(sso + "\"", sso + "&#x10D;&#x10A;Set-Cookie:flash_session=x\"")));
        try {
            String location = app.get("/auth/saml/unicode/login").expectStatus(302).header("Location");
            assertTrue(location.startsWith(sso + "%C4%8D%C4%8ASet-Cookie:flash_session=x?SAMLRequest="), location);
        } finally {
            samlModule.saml().unregister("unicode");
            unicode.close();
        }
    }

    /** IIP-MD07: a key of another family listed first, during an algorithm rollover, does not stop the right one from verifying. */
    @Test
    void anyOfTheProvidersKeysVerifiesWhateverItsFamily() throws Exception {
        for (String algorithm : List.of("EC", "RSA")) {
            FakeSamlIdp rolling = start(idp.credential());
            String other = FakeSamlIdp.Credential.generate(algorithm).certificatePem().replaceAll("-----[A-Z ]+-----|\\s", "");
            samlModule.saml().register(SamlIdp.fromXml("rolling", rolling.metadata().replaceFirst("<md:KeyDescriptor",
                    "<md:KeyDescriptor use=\"signing\"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>" + other + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor><md:KeyDescriptor")));
            try {
                Login login = login("rolling");
                post(rolling.respond(login.location()), login.cookie()).expectStatus(303);
            } finally {
                samlModule.saml().unregister("rolling");
                rolling.close();
            }
        }
    }

    /** An EC provider signs assertions and redirects with ECDSA: r and s side by side, as authentik sends them, or DER. */
    @Test
    void anEcdsaProviderSignsInAndLogsOutWithEitherSignatureEncoding() throws Exception {
        FakeSamlIdp.Credential credential = FakeSamlIdp.Credential.generate("EC");
        FakeSamlIdp ecdsa = start(credential);
        samlModule.saml().register(SamlIdp.of("ecdsa", ecdsa.metadataUrl()));
        try {
            String session = session(ecdsa, "ecdsa");
            String p1363 = ecdsa.logoutRequest(SLO, "alice");
            app.request().header("Cookie", session).get(p1363.substring(ORIGIN.length())).expectStatus(302);
            app.request().header("Cookie", session).get("/me").expectStatus(401);

            session = session(ecdsa, "ecdsa");
            String query = ecdsa.logoutRequest(SLO, "alice");
            query = query.substring(query.indexOf('?') + 1, query.indexOf("&Signature="));
            var der = Signature.getInstance("SHA256withECDSA");
            der.initSign(KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(
                    Base64.getMimeDecoder().decode(credential.privateKeyPem().replaceAll("-----[A-Z ]+-----", "")))));
            der.update(query.getBytes(StandardCharsets.US_ASCII));
            String signed = "/auth/saml/slo?" + query + "&Signature=" + URLEncoder.encode(Base64.getEncoder().encodeToString(der.sign()), StandardCharsets.UTF_8);
            app.request().header("Cookie", session).get(signed).expectStatus(302);
            app.request().header("Cookie", session).get("/me").expectStatus(401);
        } finally {
            samlModule.saml().unregister("ecdsa");
            ecdsa.close();
        }
    }

    @Test
    void aRuntimeProviderCannotTakeAConfiguredIdOrALocalAddressUnlessAllowed() {
        assertThrows(IllegalArgumentException.class, () -> samlModule.saml().register(SamlIdp.of("corp", idp.metadataUrl())));
        Saml guarded = new SamlModule(SamlKeys.of(sp.privateKeyPem(), sp.certificatePem())).saml();
        assertThrows(IllegalArgumentException.class, () -> guarded.register(SamlIdp.of("plain", idp.metadataUrl())));
        assertThrows(IllegalArgumentException.class, () -> guarded.register(SamlIdp.of("loopback", "https://127.0.0.1/metadata")));
        assertThrows(IllegalArgumentException.class, () -> guarded.register(SamlIdp.of("private", "https://10.0.0.1/metadata")));
        String huge = idp.metadata().replace("http://127.0.0.1", "https://127.0.0.1").replace("</md:EntityDescriptor>", "<!--" + "x".repeat(1 << 20) + "--></md:EntityDescriptor>");
        assertThrows(IllegalArgumentException.class, () -> guarded.register(SamlIdp.fromXml("huge", huge)), "inline metadata is bounded like a download");
    }

    @Test
    void theBootNeedsAnOrigin() {
        FlashTest broken = FlashTest.of(() -> App.create().install(new SecurityModule(), new SamlModule(SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()))));
        Throwable failure = assertThrows(Exception.class, () -> broken.get("/auth/saml/metadata"));
        while (failure.getCause() != null && !String.valueOf(failure.getMessage()).contains("origin")) failure = failure.getCause();
        assertTrue(String.valueOf(failure.getMessage()).contains("SecurityModule.origin"), String.valueOf(failure));
    }

    @Test
    void whatTheModulePublishesOrAcceptsIsChecked() {
        SamlModule module = new SamlModule(SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()));
        assertThrows(IllegalArgumentException.class, () -> module.logo("http://app.test/logo.png", 80, 60));
        module.logo("https://app.test/logo.png", 80, 60).logo("data:image/png;base64,iVBORw0KGgo=", 80, 60);
        assertThrows(IllegalArgumentException.class, () -> module.algorithms(Xml.SHA256, Xml.RSA_SHA256, Xml.AES256_GCM, Xml.RSA_1_5));
    }

    /** Same modulus, wrong CRT exponent: the pair cannot sign what the certificate verifies, and is refused when configured. */
    @Test
    void aKeyThatOnlySharesTheCertificatesModulusIsRefused() throws Exception {
        var key = (RSAPrivateCrtKey) SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()).privateKeys.getFirst();
        var broken = KeyFactory.getInstance("RSA").generatePrivate(new RSAPrivateCrtKeySpec(key.getModulus(), key.getPublicExponent(), key.getPrivateExponent(),
                key.getPrimeP(), key.getPrimeQ(), key.getPrimeExponentP().add(BigInteger.ONE), key.getPrimeExponentQ(), key.getCrtCoefficient()));
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(broken.getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        assertThrows(IllegalArgumentException.class, () -> SamlKeys.of(pem, sp.certificatePem()));
    }

    @Test
    void keysAreCheckedWhenConfigured() {
        assertThrows(IllegalArgumentException.class, () -> SamlKeys.of(sp.privateKeyPem(), retired.certificatePem()));
        assertThrows(IllegalArgumentException.class, () -> SamlKeys.of("-----BEGIN RSA PRIVATE KEY-----\nAAAA\n-----END RSA PRIVATE KEY-----", sp.certificatePem()));
        assertEquals(2, SamlKeys.of(sp.privateKeyPem(), sp.certificatePem()).and(retired.privateKeyPem(), retired.certificatePem()).privateKeys.size());
    }
}
