package dev.relism.flash.ext.security.saml;

import dev.relism.flash.App;
import dev.relism.flash.Module;
import dev.relism.flash.ext.security.LoginMethod;
import dev.relism.flash.ext.security.SessionLogout;

import java.time.Duration;
import java.util.Set;

/**
 * SAML 2.0 for an app, as a service provider to any number of identity providers: sign-in that ends
 * in a session, Single Logout both ways, and this application's metadata for providers to import.
 * Needs {@code SecurityModule} beside it, with its {@code origin} set.
 *
 * <pre>{@code
 * app.install(new SecurityModule().origin("https://app.example"),
 *             new SamlModule(SamlKeys.of(keyPem, certificatePem), SamlIdp.of("corp", "https://idp.example.com/metadata")));
 * }</pre>
 *
 * <p>Routes: {@code GET /auth/saml/metadata}, {@code GET /auth/saml/{idp}/login}, {@code GET /auth/saml/login}
 * (a discovery service's return), {@code POST /auth/saml/acs} and {@code GET /auth/saml/slo}.
 */
public final class SamlModule implements Module {

    private final Saml saml;

    public SamlModule(SamlKeys keys, SamlIdp... providers) {
        this.saml = new Saml(keys, providers);
    }

    /**
     * The service this module installs, for what is written before the app starts. A class takes it as
     * a constructor parameter instead.
     */
    public Saml saml() {
        return saml;
    }

    /**
     * What this application's metadata tells people about it (SAML2Int SDP-MD09, MD11): the name and
     * privacy statement a provider shows when asking a user to consent, and who to write to when it
     * breaks. Federations require them; a provider configured by hand does not.
     */
    public SamlModule info(String displayName, String privacyStatementUrl, String technicalContactEmail) {
        saml.displayName = displayName;
        saml.privacyStatementUrl = privacyStatementUrl;
        saml.technicalContact = technicalContactEmail;
        return this;
    }

    /** The logo of {@link #info}: an https URL or a data URI, and its size in pixels. */
    public SamlModule logo(String url, int width, int height) {
        if (!url.startsWith("https://") && !url.startsWith("data:image/")) throw new IllegalArgumentException("A logo is an https URL or a data:image URI (SAML2Int SDP-MD10): " + url);
        saml.logo = "<mdui:Logo width=\"" + width + "\" height=\"" + height + "\">" + Xml.escape(url) + "</mdui:Logo>";
        return this;
    }

    /** How far apart this server's clock and a provider's may be; 3 minutes by default (SAML2Int SDP-G01). */
    public SamlModule clockSkew(Duration skew) {
        saml.clockSkew = skew;
        return this;
    }

    /**
     * The XML Signature and Encryption algorithm URIs accepted, replacing the defaults (IIP-ALG08):
     * SHA-2 digests, RSA and ECDSA with SHA-2, AES-GCM and AES-CBC, RSA-OAEP. Anything else is refused,
     * and RSA PKCS#1 v1.5 key transport cannot be accepted at all: its padding oracle gives the key away.
     */
    public SamlModule algorithms(String... uris) {
        Set<String> accepted = Set.of(uris);
        if (accepted.contains(Xml.RSA_1_5)) throw new IllegalArgumentException("RSA PKCS#1 v1.5 key transport is never accepted: " + Xml.RSA_1_5);
        saml.algorithms = accepted;
        return this;
    }

    /**
     * Lets providers registered at runtime publish metadata on private or loopback addresses and plain
     * http: development only, since otherwise registering one is a request forgery against the server's
     * own network.
     */
    public SamlModule allowLocalProviders() {
        saml.localProviders = true;
        return this;
    }

    @Override
    public void install(App app) {
        app.add(Saml.class, saml)
                .add(SessionLogout.class, saml)
                .get("/auth/saml/metadata", saml::metadata)
                .get("/auth/saml/login", saml::discovered)
                .get("/auth/saml/{idp}/login", saml::login)
                .post("/auth/saml/acs", saml::consume)
                .get("/auth/saml/slo", saml::logout);
        for (SamlIdp provider : saml.configured) {
            app.add(LoginMethod.class, new LoginMethod(provider.id, provider.name, "/auth/saml/" + provider.id + "/login", LoginMethod.Kind.REDIRECT));
        }
    }
}
