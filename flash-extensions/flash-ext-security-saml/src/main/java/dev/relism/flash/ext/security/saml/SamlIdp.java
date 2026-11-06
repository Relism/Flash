package dev.relism.flash.ext.security.saml;

import java.util.List;

/**
 * A SAML identity provider the application trusts, known by its metadata: where to send users, the
 * keys it signs with, how it logs out. Everything else is read from there (SAML2Int SDP-MD01), so no
 * provider needs a setting of its own.
 *
 * <pre>{@code
 * SamlIdp.of("corp", "https://idp.example.com/metadata").name("Corp SSO")
 * }</pre>
 */
public final class SamlIdp {

    final String id;
    final String metadataUrl;
    final String metadataXml;
    String name;
    String entityId;
    String metadataSigner;
    List<String> authnContext = List.of();
    boolean unsolicited;
    boolean signedResponses;

    private SamlIdp(String id, String metadataUrl, String metadataXml) {
        if (!id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("SAML identity provider id must be URL-safe: " + id);
        this.id = id;
        this.name = id;
        this.metadataUrl = metadataUrl;
        this.metadataXml = metadataXml;
    }

    /**
     * Metadata fetched from {@code metadataUrl} when the app starts and again every hour, trusted for
     * the TLS connection it comes over.
     *
     * @param id names the provider in routes ({@code /auth/saml/{id}/login}) and principals
     */
    public static SamlIdp of(String id, String metadataUrl) {
        return new SamlIdp(id, metadataUrl, null);
    }

    /** Metadata the provider's administrator handed over as a document, trusted as configured. */
    public static SamlIdp fromXml(String id, String metadataXml) {
        return new SamlIdp(id, null, metadataXml);
    }

    /** What a sign-in page shows. Default: the id. */
    public SamlIdp name(String name) {
        this.name = name;
        return this;
    }

    /** Which provider of an aggregate, an {@code EntitiesDescriptor} a federation publishes. */
    public SamlIdp entityId(String entityId) {
        this.entityId = entityId;
        return this;
    }

    /**
     * Trusts the metadata for its signature with this X.509 certificate in PEM, a federation's,
     * rather than for where it came from; it must then say until when it is valid (IIP-MD03, MD04).
     */
    public SamlIdp metadataSigner(String certificatePem) {
        this.metadataSigner = certificatePem;
        return this;
    }

    /** Asks for one of these authentication contexts, compared exactly, and refuses an assertion of any other (IIP-SP06, SP07). */
    public SamlIdp authnContext(String... classRefs) {
        this.authnContext = List.of(classRefs);
        return this;
    }

    /**
     * Accepts sign-ins the provider starts on its own, from its portal. Off by default: such a
     * response answers no request of this browser, so it cannot tell a user's sign-in from someone
     * else's response posted through their browser.
     */
    public SamlIdp unsolicited() {
        this.unsolicited = true;
        return this;
    }

    /**
     * Refuses a response whose envelope is not signed (IIP-SP13). Otherwise a signed assertion is
     * enough, which is how most providers sign by default: what the envelope says that matters, the
     * recipient and the request answered, the assertion repeats under its own signature.
     */
    public SamlIdp signedResponses() {
        this.signedResponses = true;
        return this;
    }

    public String id() {
        return id;
    }
}
