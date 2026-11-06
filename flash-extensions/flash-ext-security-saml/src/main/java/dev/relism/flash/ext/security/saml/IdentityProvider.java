package dev.relism.flash.ext.security.saml;

import dev.relism.flash.ext.security.PublicUrl;
import org.w3c.dom.Element;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static dev.relism.flash.ext.security.saml.Xml.DS;
import static dev.relism.flash.ext.security.saml.Xml.MD;
import static dev.relism.flash.ext.security.saml.Xml.attribute;
import static dev.relism.flash.ext.security.saml.Xml.children;

/**
 * One identity provider as its metadata describes it, read once per refresh: where its users sign in
 * and out, the keys its messages must verify with. Immutable; a refresh replaces it.
 */
final class IdentityProvider {

    static final String REDIRECT = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect";
    static final String POST = "urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST";
    private static final String PROTOCOL = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String SHIBMD = "urn:mace:shibboleth:metadata:1.0";

    /** A provider that does not answer fails the boot or the refresh in seconds, never hangs it. */
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** The whole download, body included, which {@code HttpRequest.timeout} does not cover. */
    private static final Duration DEADLINE = Duration.ofSeconds(30);
    /** A provider registered at runtime publishes one entity; a configured URL may be a federation's aggregate. */
    private static final int MAX_GUARDED = 1 << 20, MAX_CONFIGURED = 256 << 20;
    static final Duration REFRESH = Duration.ofHours(1);
    /** Signed metadata valid for longer than this could be replayed long after its keys were revoked (IIP-MD04). */
    private static final Duration MAX_VALIDITY = Duration.ofDays(365);

    final SamlIdp config;
    final boolean guarded;
    final String entityId;
    final String sso;
    final boolean ssoByPost;
    final String slo;
    final String sloResponse;
    final List<PublicKey> keys;
    /** {@code shibmd:Scope}s the provider may give its users' identifiers; {@code null} when it declares none. */
    final Set<String> scopes;
    final Instant validUntil;
    final Instant refreshAt;

    /**
     * Fetches and reads {@code config}'s metadata. {@code guarded} providers, registered at runtime from
     * input the operator does not control, must be fetched over https from public addresses, redirects
     * included: otherwise registering one is a request forgery against the server's own network.
     */
    IdentityProvider(SamlIdp config, boolean guarded, Set<String> algorithms) {
        this.config = config;
        this.guarded = guarded;
        Element entity;
        Instant validUntil;
        try {
            Element root = Xml.parse(config.metadataXml != null ? config.metadataXml.getBytes(StandardCharsets.UTF_8) : fetch(config.metadataUrl, guarded)).getDocumentElement();
            validUntil = Xml.instant(root, "validUntil");
            if (config.metadataSigner != null) {
                PublicKey signer = CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(config.metadataSigner.getBytes(StandardCharsets.US_ASCII))).getPublicKey();
                if (!Xml.verify(root, List.of(signer), algorithms)) throw new IllegalArgumentException("The metadata is not signed");
                if (validUntil == null) throw new IllegalArgumentException("Signed metadata must say until when it is valid");
                if (validUntil.isAfter(Instant.now().plus(MAX_VALIDITY))) throw new IllegalArgumentException("The metadata claims validity beyond a year");
            }
            entity = entity(root, config.entityId);
            // Metadata 2.3.1: the earliest validUntil on the way from the root to the entity bounds it.
            for (org.w3c.dom.Node n = entity; n != root && n instanceof Element e; n = n.getParentNode()) {
                Instant nested = Xml.instant(e, "validUntil");
                if (nested != null && (validUntil == null || nested.isBefore(validUntil))) validUntil = nested;
            }
        } catch (IllegalArgumentException refused) {
            throw new IllegalArgumentException("SAML identity provider " + config.id + ": " + refused.getMessage(), refused);
        } catch (Exception unreadable) {
            throw new IllegalStateException("SAML identity provider " + config.id + ": metadata unreadable at "
                    + (config.metadataUrl != null ? config.metadataUrl : "the given XML"), unreadable);
        }
        if (validUntil != null && validUntil.isBefore(Instant.now())) throw new IllegalArgumentException("SAML identity provider " + config.id + ": metadata expired " + validUntil);
        Element idp = null;
        for (Element descriptor : children(entity, MD, "IDPSSODescriptor")) {
            if (descriptor.getAttributeNS(null, "protocolSupportEnumeration").contains(PROTOCOL)) idp = descriptor;
        }
        if (idp == null) throw new IllegalArgumentException("SAML identity provider " + config.id + ": no SAML 2.0 IDPSSODescriptor");
        this.entityId = entity.getAttributeNS(null, "entityID");
        this.validUntil = validUntil;
        // Fetched again before validUntil, not after: past it, the provider is refused until the next fetch.
        Instant refresh = Instant.now().plus(REFRESH), early = validUntil == null ? null : validUntil.minus(Duration.ofMinutes(10));
        this.refreshAt = early != null && early.isBefore(refresh) ? early : refresh;

        String redirect = endpoint(idp, "SingleSignOnService", REDIRECT, "Location", guarded, config.id);
        this.ssoByPost = redirect == null;
        this.sso = redirect != null ? redirect : endpoint(idp, "SingleSignOnService", POST, "Location", guarded, config.id);
        if (sso == null) throw new IllegalArgumentException("SAML identity provider " + config.id + ": no SingleSignOnService for HTTP-Redirect or HTTP-POST");
        this.slo = endpoint(idp, "SingleLogoutService", REDIRECT, "Location", guarded, config.id);
        String response = endpoint(idp, "SingleLogoutService", REDIRECT, "ResponseLocation", guarded, config.id);
        this.sloResponse = response != null ? response : slo;
        this.keys = signingKeys(idp, config.id);

        Element extensions = Xml.child(idp, MD, "Extensions");
        List<Element> declared = extensions == null ? List.of() : children(extensions, SHIBMD, "Scope");
        Set<String> scopes = new HashSet<>();
        for (Element scope : declared) {
            // SAML2Int SDP-SP17: a regular expression may be ignored, and is: a provider's own pattern run on its own
            // input is a denial of service waiting to happen. Only literal scopes bind, and declaring any binds.
            if (!"true".equals(attribute(scope, "regexp")) && !"1".equals(attribute(scope, "regexp"))) scopes.add(Xml.text(scope));
        }
        this.scopes = declared.isEmpty() ? null : Set.copyOf(scopes);
    }

    /** Whether its metadata still vouches for it: past {@code validUntil}, nothing it signs is trusted (IIP-MD04). */
    boolean valid() {
        return validUntil == null || validUntil.isAfter(Instant.now());
    }

    /**
     * The metadata at {@code url}, following redirects (IIP-MD02) under the same address rules and never
     * from https down to http, within {@link #DEADLINE} and a size bound: a slow or endless body would
     * otherwise hold the caller, or exhaust the heap.
     */
    private static byte[] fetch(String url, boolean guarded) throws Exception {
        URI location = URI.create(url);
        for (int hop = 0; hop <= 5; hop++) {
            if (guarded) PublicUrl.require(location.toString());
            HttpResponse<InputStream> response = HTTP.send(HttpRequest.newBuilder(location).timeout(TIMEOUT).build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                String next = response.headers().firstValue("Location").orElse(null);
                if (next == null || response.statusCode() / 100 != 3) {
                    if (response.statusCode() != 200) throw new IllegalStateException("Metadata answered " + response.statusCode());
                    CompletableFuture.delayedExecutor(DEADLINE.toMillis(), TimeUnit.MILLISECONDS).execute(() -> close(body));
                    int max = guarded ? MAX_GUARDED : MAX_CONFIGURED;
                    byte[] metadata = body.readNBytes(max + 1);
                    if (metadata.length > max) throw new IllegalArgumentException("Metadata larger than " + max + " bytes");
                    return metadata;
                }
                URI previous = location;
                location = location.resolve(next);
                if ("https".equals(previous.getScheme()) && !"https".equals(location.getScheme())) throw new IllegalArgumentException("Metadata redirects from https to " + location.getScheme());
            }
        }
        throw new IllegalStateException("Metadata redirects more than 5 times");
    }

    private static void close(InputStream body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // the read it ends reports the failure
        }
    }

    /** The root itself, or the entity of an aggregate named by {@code entityId}, however deeply aggregates nest. */
    private static Element entity(Element root, String entityId) {
        if (Xml.is(root, MD, "EntityDescriptor")) {
            if (entityId != null && !entityId.equals(root.getAttributeNS(null, "entityID"))) throw new IllegalArgumentException("The metadata describes " + root.getAttributeNS(null, "entityID"));
            return root;
        }
        if (!Xml.is(root, MD, "EntitiesDescriptor")) throw new IllegalArgumentException("Not SAML metadata: " + root.getLocalName());
        if (entityId == null) throw new IllegalArgumentException("The metadata is an aggregate: name the provider with entityId(...)");
        org.w3c.dom.NodeList entities = root.getElementsByTagNameNS(MD, "EntityDescriptor");
        for (int i = 0; i < entities.getLength(); i++) {
            if (entities.item(i) instanceof Element candidate && entityId.equals(candidate.getAttributeNS(null, "entityID"))) return candidate;
        }
        throw new IllegalArgumentException("The aggregate has no " + entityId);
    }

    /**
     * Where the browser is sent for {@code service} by {@code binding}: by a Location header or a form this
     * origin serves, so nothing but an absolute http(s) URL, https only for a guarded provider, and in ASCII.
     * A character beyond it would reach the header cut to its low byte: U+010D U+010A is a line break.
     */
    private static String endpoint(Element idp, String service, String binding, String attribute, boolean guarded, String id) {
        for (Element endpoint : children(idp, MD, service)) {
            if (!binding.equals(endpoint.getAttributeNS(null, "Binding"))) continue;
            String url = attribute(endpoint, attribute);
            if (url == null) return null;
            URI uri;
            try {
                uri = new URI(url);
            } catch (java.net.URISyntaxException invalid) {
                throw new IllegalArgumentException("SAML identity provider " + id + ": " + service + " is not a URL: " + invalid.getInput());
            }
            if (!(uri.isAbsolute() && uri.getHost() != null && ("https".equals(uri.getScheme()) || !guarded && "http".equals(uri.getScheme())))) {
                throw new IllegalArgumentException("SAML identity provider " + id + ": " + service + " is not an " + (guarded ? "https" : "http(s)") + " URL: " + url);
            }
            return uri.toASCIIString();
        }
        return null;
    }

    /**
     * Every certificate of a {@code KeyDescriptor} for signing, or for any use when it names none
     * (IIP-MD07, MD11). A certificate only carries its key: its dates and issuer are not checked (IIP-MD12),
     * its key size is (SAML2Int SDP-MD06, MD07), and a weaker one is left out rather than failing the rest.
     */
    private static List<PublicKey> signingKeys(Element idp, String id) {
        List<PublicKey> keys = new ArrayList<>(2);
        try {
            CertificateFactory x509 = CertificateFactory.getInstance("X.509");
            for (Element descriptor : children(idp, MD, "KeyDescriptor")) {
                if ("encryption".equals(attribute(descriptor, "use"))) continue;
                Element info = Xml.child(descriptor, DS, "KeyInfo");
                if (info != null) for (Element data : children(info, DS, "X509Data")) for (Element certificate : children(data, DS, "X509Certificate")) {
                    PublicKey key = x509.generateCertificate(new ByteArrayInputStream(Base64.getMimeDecoder().decode(Xml.text(certificate)))).getPublicKey();
                    boolean strong = key instanceof RSAPublicKey rsa ? rsa.getModulus().bitLength() >= 2048
                            : key instanceof ECPublicKey ec && ec.getParams().getCurve().getField().getFieldSize() >= 256;
                    if (strong) keys.add(key);
                }
            }
        } catch (Exception unreadable) {
            throw new IllegalArgumentException("SAML identity provider " + id + ": unreadable signing certificate", unreadable);
        }
        if (keys.isEmpty()) throw new IllegalArgumentException("SAML identity provider " + id + ": no signing certificate of RSA 2048 or EC 256 bits or more");
        return List.copyOf(keys);
    }
}
