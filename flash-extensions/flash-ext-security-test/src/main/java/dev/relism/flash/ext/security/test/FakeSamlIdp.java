package dev.relism.flash.ext.security.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.apache.xml.security.Init;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.encryption.EncryptedData;
import org.apache.xml.security.encryption.EncryptedKey;
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.keys.KeyInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.crypto.KeyGenerator;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * A SAML identity provider on a random local port, serving its metadata. Sign-in is approved at once,
 * as {@link #signInAs}: {@link #respond} answers the request the application redirected to it with the
 * {@code SAMLResponse} a browser would post back.
 */
public final class FakeSamlIdp implements AutoCloseable {

    private static final String SAMLP = "urn:oasis:names:tc:SAML:2.0:protocol";
    private static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    private static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    private static final String ECDSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256";

    static {
        Init.init();
    }

    /** A private key and its self-signed certificate, PKCS#8 and X.509 in PEM, as {@code SamlKeys.of} takes them. */
    public record Credential(String privateKeyPem, String certificatePem) {

        /** A new RSA 2048 key, made by the JDK's {@code keytool}: the JDK has no API that makes a certificate. */
        public static Credential generate() {
            return generate("RSA");
        }

        /** A new key of {@code algorithm}, {@code RSA} (2048 bits) or {@code EC} (P-256). */
        public static Credential generate(String algorithm) {
            Path store = null;
            Process keytool = null;
            try {
                store = Files.createTempFile("fake-saml", ".p12");
                Files.delete(store); // keytool creates it, and refuses one that exists
                keytool = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
                        "-genkeypair", "-alias", "k", "-keyalg", algorithm, "-keysize", "EC".equals(algorithm) ? "256" : "2048", "-validity", "3650", "-dname", "CN=fake",
                        "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit").redirectErrorStream(true).start();
                if (keytool.waitFor() != 0) throw new IllegalStateException(new String(keytool.getInputStream().readAllBytes()));
                KeyStore keys = KeyStore.getInstance("PKCS12");
                try (FileInputStream in = new FileInputStream(store.toFile())) {
                    keys.load(in, "changeit".toCharArray());
                }
                return new Credential(pem("PRIVATE KEY", keys.getKey("k", "changeit".toCharArray()).getEncoded()),
                        pem("CERTIFICATE", keys.getCertificate("k").getEncoded()));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while keytool ran", interrupted);
            } catch (Exception e) {
                throw new IllegalStateException("Could not generate a key with keytool", e);
            } finally {
                if (keytool != null) keytool.destroy();
                try {
                    if (store != null) Files.deleteIfExists(store); // it holds a private key
                } catch (IOException ignored) {
                    // a temporary file the system cleans up
                }
            }
        }

        private static String pem(String type, byte[] der) {
            return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type + "-----\n";
        }

        PrivateKey privateKey() throws Exception {
            String base64 = privateKeyPem.replaceAll("-----[A-Z ]+-----", "");
            return java.security.KeyFactory.getInstance(certificate().getPublicKey().getAlgorithm()).generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getMimeDecoder().decode(base64)));
        }

        X509Certificate certificate() throws Exception {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificatePem.getBytes(StandardCharsets.US_ASCII)));
        }
    }

    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final String base = "http://127.0.0.1:" + server.getAddress().getPort();
    private final Credential credential;
    private final PrivateKey key;
    private final boolean ec;
    private volatile String nameId = "alice";
    private volatile Map<String, List<String>> attributes = Map.of();
    private volatile boolean signResponse = true;
    private volatile boolean signAssertion = true;
    private volatile X509Certificate encryptTo;
    private volatile String encryption;
    private volatile UnaryOperator<String> rewrite = UnaryOperator.identity();

    public FakeSamlIdp() throws Exception {
        this(Credential.generate());
    }

    /**
     * One that signs with {@code credential}: two fakes sharing one stand for one provider rotating keys, or an
     * impostor. An EC credential signs with ECDSA, its redirect signatures r and s side by side, as authentik does.
     */
    public FakeSamlIdp(Credential credential) throws Exception {
        this.credential = credential;
        this.key = credential.privateKey();
        this.ec = key.getAlgorithm().equals("EC");
        server.createContext("/metadata", exchange -> send(exchange, metadata()));
        server.start();
    }

    public String metadataUrl() {
        return base + "/metadata";
    }

    public String entityId() {
        return base + "/idp";
    }

    public Credential credential() {
        return credential;
    }

    /** This provider's metadata: SSO and logout by HTTP-Redirect at {@code /sso} and {@code /slo}, and its signing certificate. */
    public String metadata() {
        String certificate = credential.certificatePem.replaceAll("-----[A-Z ]+-----|\\s", "");
        return "<md:EntityDescriptor xmlns:md=\"urn:oasis:names:tc:SAML:2.0:metadata\" xmlns:ds=\"http://www.w3.org/2000/09/xmldsig#\" entityID=\"" + entityId() + "\">"
                + "<md:IDPSSODescriptor protocolSupportEnumeration=\"" + SAMLP + "\"><md:KeyDescriptor use=\"signing\"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>"
                + certificate + "</ds:X509Certificate></ds:X509Data></ds:KeyInfo></md:KeyDescriptor>"
                + "<md:SingleLogoutService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\"" + base + "/slo\"/>"
                + "<md:SingleSignOnService Binding=\"urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect\" Location=\"" + base + "/sso\"/>"
                + "</md:IDPSSODescriptor></md:EntityDescriptor>";
    }

    /** {@link #metadata()} valid until {@code validUntil}, when not {@code null}, and signed with this provider's key, as a federation signs its own. */
    public String signedMetadata(Instant validUntil) throws Exception {
        String unsigned = metadata().replaceFirst(" entityID=", " ID=\"_m" + UUID.randomUUID() + "\""
                + (validUntil == null ? "" : " validUntil=\"" + validUntil.truncatedTo(ChronoUnit.SECONDS) + "\"") + " entityID=");
        Document document = XMLUtils.read(new ByteArrayInputStream(unsigned.getBytes(StandardCharsets.UTF_8)), true);
        sign(document.getDocumentElement());
        return text(document);
    }

    /** Who sign-in vouches for, as a persistent {@code NameID}, with what attributes. */
    public FakeSamlIdp signInAs(String nameId, Map<String, List<String>> attributes) {
        this.nameId = nameId;
        this.attributes = attributes;
        return this;
    }

    /** What is signed: both by default. */
    public FakeSamlIdp sign(boolean response, boolean assertion) {
        this.signResponse = response;
        this.signAssertion = assertion;
        return this;
    }

    /**
     * Encrypts the assertion to the application's certificate in PEM, with RSA-OAEP and {@code algorithm},
     * such as AES-256-GCM's URI; a {@code null} certificate stops encrypting.
     */
    public FakeSamlIdp encryptTo(String certificatePem, String algorithm) throws Exception {
        this.encryptTo = certificatePem == null ? null : new Credential(null, certificatePem).certificate();
        this.encryption = algorithm;
        return this;
    }

    /** Rewrites each response and logout message before it is signed: a provider that misbehaves, or is configured wrongly. */
    public FakeSamlIdp rewrite(UnaryOperator<String> response) {
        this.rewrite = response;
        return this;
    }

    /**
     * The {@code SAMLResponse} answering the request in {@code location}, the URL the application redirected
     * the browser to: sent to its assertion consumer, for its entity, in response to its ID.
     */
    public String respond(String location) throws Exception {
        Document request = XMLUtils.read(new ByteArrayInputStream(inflate(query(location).get("SAMLRequest"))), true);
        Element root = request.getDocumentElement();
        String audience = root.getElementsByTagNameNS(SAML, "Issuer").item(0).getTextContent();
        return response(root.getAttribute("AssertionConsumerServiceURL"), audience, root.getAttribute("ID"));
    }

    /** A {@code SAMLResponse} the provider sends on its own, answering no request. */
    public String unsolicited(String acs, String audience) throws Exception {
        return response(acs, audience, null);
    }

    /** The URL of the application's logout service carrying a signed {@code LogoutRequest} for {@code nameId}. */
    public String logoutRequest(String slo, String nameId) throws Exception {
        String xml = "<samlp:LogoutRequest xmlns:samlp=\"" + SAMLP + "\" xmlns:saml=\"" + SAML + "\" ID=\"_" + UUID.randomUUID()
                + "\" Version=\"2.0\" IssueInstant=\"" + now(0) + "\" Destination=\"" + slo + "\"><saml:Issuer>" + entityId()
                + "</saml:Issuer><saml:NameID Format=\"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\">" + nameId + "</saml:NameID></samlp:LogoutRequest>";
        return slo + "?" + redirect("SAMLRequest", rewrite.apply(xml), "relay");
    }

    /** The URL of the application's logout service carrying a signed {@code LogoutResponse} to the request in {@code location}. */
    public String logoutResponse(String location, String slo) throws Exception {
        Element request = XMLUtils.read(new ByteArrayInputStream(inflate(query(location).get("SAMLRequest"))), true).getDocumentElement();
        String xml = "<samlp:LogoutResponse xmlns:samlp=\"" + SAMLP + "\" xmlns:saml=\"" + SAML + "\" ID=\"_" + UUID.randomUUID()
                + "\" Version=\"2.0\" IssueInstant=\"" + now(0) + "\" Destination=\"" + slo + "\" InResponseTo=\"" + request.getAttribute("ID")
                + "\"><saml:Issuer>" + entityId() + "</saml:Issuer><samlp:Status><samlp:StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/></samlp:Status></samlp:LogoutResponse>";
        return slo + "?" + redirect("SAMLResponse", rewrite.apply(xml), null);
    }

    /** The XML a redirect in {@code location} carries as {@code parameter}. */
    public static String redirected(String location, String parameter) throws IOException {
        return new String(inflate(query(location).get(parameter)), StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private String response(String acs, String audience, String inResponseTo) throws Exception {
        String answers = inResponseTo == null ? "" : " InResponseTo=\"" + inResponseTo + "\"";
        StringBuilder statement = new StringBuilder();
        attributes.forEach((name, values) -> {
            statement.append("<saml:Attribute Name=\"").append(name).append("\" NameFormat=\"urn:oasis:names:tc:SAML:2.0:attrname-format:uri\">");
            for (String value : values) statement.append("<saml:AttributeValue>").append(value).append("</saml:AttributeValue>");
            statement.append("</saml:Attribute>");
        });
        String xml = "<samlp:Response xmlns:samlp=\"" + SAMLP + "\" xmlns:saml=\"" + SAML + "\" ID=\"_r" + UUID.randomUUID() + "\" Version=\"2.0\" IssueInstant=\""
                + now(0) + "\" Destination=\"" + acs + "\"" + answers + "><saml:Issuer>" + entityId() + "</saml:Issuer>"
                + "<samlp:Status><samlp:StatusCode Value=\"urn:oasis:names:tc:SAML:2.0:status:Success\"/></samlp:Status>"
                + "<saml:Assertion ID=\"_a" + UUID.randomUUID() + "\" Version=\"2.0\" IssueInstant=\"" + now(0) + "\"><saml:Issuer>" + entityId() + "</saml:Issuer>"
                + "<saml:Subject><saml:NameID Format=\"urn:oasis:names:tc:SAML:2.0:nameid-format:persistent\">" + nameId + "</saml:NameID>"
                + "<saml:SubjectConfirmation Method=\"urn:oasis:names:tc:SAML:2.0:cm:bearer\"><saml:SubjectConfirmationData NotOnOrAfter=\"" + now(300)
                + "\" Recipient=\"" + acs + "\"" + answers + "/></saml:SubjectConfirmation></saml:Subject>"
                + "<saml:Conditions NotBefore=\"" + now(-60) + "\" NotOnOrAfter=\"" + now(300) + "\"><saml:AudienceRestriction><saml:Audience>" + audience
                + "</saml:Audience></saml:AudienceRestriction></saml:Conditions>"
                + "<saml:AuthnStatement AuthnInstant=\"" + now(0) + "\" SessionIndex=\"_s" + UUID.randomUUID() + "\"><saml:AuthnContext><saml:AuthnContextClassRef>"
                + "urn:oasis:names:tc:SAML:2.0:ac:classes:PasswordProtectedTransport</saml:AuthnContextClassRef></saml:AuthnContext></saml:AuthnStatement>"
                + (statement.isEmpty() ? "" : "<saml:AttributeStatement>" + statement + "</saml:AttributeStatement>")
                + "</saml:Assertion></samlp:Response>";
        Document document = XMLUtils.read(new ByteArrayInputStream(rewrite.apply(xml).getBytes(StandardCharsets.UTF_8)), true);
        Element response = document.getDocumentElement();
        Element assertion = (Element) response.getElementsByTagNameNS(SAML, "Assertion").item(0);
        if (signAssertion) sign(assertion);
        if (encryptTo != null) encrypt(document, assertion);
        if (signResponse) sign(response);
        return Base64.getEncoder().encodeToString(text(document).getBytes(StandardCharsets.UTF_8));
    }

    private static String text(Document document) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLUtils.outputDOM(document, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** An enveloped signature over {@code element}, after its {@code Issuer} as SAML's schema puts it, or first when it has none (metadata). */
    private void sign(Element element) throws Exception {
        Document document = element.getOwnerDocument();
        element.setIdAttributeNS(null, "ID", true);
        XMLSignature signature = new XMLSignature(document, "", ec ? ECDSA_SHA256 : RSA_SHA256, Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        org.w3c.dom.Node issuer = element.getElementsByTagNameNS(SAML, "Issuer").item(0);
        element.insertBefore(signature.getElement(), issuer == null ? element.getFirstChild() : issuer.getNextSibling());
        Transforms transforms = new Transforms(document);
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        transforms.addTransform(Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        signature.addDocument("#" + element.getAttribute("ID"), transforms, "http://www.w3.org/2001/04/xmlenc#sha256");
        signature.sign(key);
    }

    private void encrypt(Document document, Element assertion) throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(encryption.contains("128") ? 128 : 256);
        javax.crypto.SecretKey secret = generator.generateKey();
        XMLCipher wrap = XMLCipher.getInstance(XMLCipher.RSA_OAEP);
        wrap.init(XMLCipher.WRAP_MODE, encryptTo.getPublicKey());
        EncryptedKey encryptedKey = wrap.encryptKey(document, secret);
        XMLCipher cipher = XMLCipher.getInstance(encryption);
        cipher.init(XMLCipher.ENCRYPT_MODE, secret);
        EncryptedData data = cipher.getEncryptedData();
        KeyInfo info = new KeyInfo(document);
        info.add(encryptedKey);
        data.setKeyInfo(info);
        Element container = document.createElementNS(SAML, "saml:EncryptedAssertion");
        assertion.getParentNode().replaceChild(container, assertion);
        container.appendChild(assertion);
        cipher.doFinal(document, assertion, false);
    }

    private String redirect(String parameter, String xml, String relayState) throws Exception {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        deflater.setInput(xml.getBytes(StandardCharsets.UTF_8));
        deflater.finish();
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (!deflater.finished()) deflated.write(buffer, 0, deflater.deflate(buffer));
        deflater.end();
        String query = parameter + "=" + encode(Base64.getEncoder().encodeToString(deflated.toByteArray()))
                + (relayState == null ? "" : "&RelayState=" + encode(relayState)) + "&SigAlg=" + encode(ec ? ECDSA_SHA256 : RSA_SHA256);
        Signature signature = Signature.getInstance(ec ? "SHA256withECDSAinP1363Format" : "SHA256withRSA");
        signature.initSign(key);
        signature.update(query.getBytes(StandardCharsets.US_ASCII));
        return query + "&Signature=" + encode(Base64.getEncoder().encodeToString(signature.sign()));
    }

    private static byte[] inflate(String base64) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(base64)), new Inflater(true))) {
            return in.readAllBytes();
        }
    }

    private static Map<String, String> query(String location) {
        Map<String, String> values = new HashMap<>();
        for (String pair : URI.create(location).getRawQuery().split("&")) {
            int eq = pair.indexOf('=');
            values.put(pair.substring(0, eq), URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return values;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String now(long seconds) {
        return Instant.now().plusSeconds(seconds).truncatedTo(ChronoUnit.SECONDS).toString();
    }

    private static void send(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/samlmetadata+xml");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
