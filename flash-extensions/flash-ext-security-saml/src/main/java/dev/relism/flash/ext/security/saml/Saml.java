package dev.relism.flash.ext.security.saml;

import dev.relism.flash.Bound;
import dev.relism.flash.Services;
import dev.relism.flash.ext.security.AuthenticationFailedException;
import dev.relism.flash.ext.security.Principal;
import dev.relism.flash.ext.security.Redirects;
import dev.relism.flash.ext.security.Security;
import dev.relism.flash.ext.security.SecurityIdentity;
import dev.relism.flash.ext.security.Session;
import dev.relism.flash.ext.security.SessionLogout;
import dev.relism.flash.ext.security.Tokens;
import dev.relism.flash.http.ContentType;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.QueryParams;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import lombok.extern.slf4j.Slf4j;
import org.w3c.dom.Element;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static dev.relism.flash.ext.security.saml.Xml.SAML;
import static dev.relism.flash.ext.security.saml.Xml.SAMLP;
import static dev.relism.flash.ext.security.saml.Xml.attribute;
import static dev.relism.flash.ext.security.saml.Xml.child;
import static dev.relism.flash.ext.security.saml.Xml.children;
import static dev.relism.flash.ext.security.saml.Xml.escape;
import static dev.relism.flash.ext.security.saml.Xml.instant;
import static dev.relism.flash.ext.security.saml.Xml.required;
import static dev.relism.flash.ext.security.saml.Xml.text;

/**
 * SAML 2.0 for one app, as a service provider: Web Browser SSO into a {@link Security} session, and
 * Single Logout both ways. Installed by {@link SamlModule}; a handler that connects providers at
 * runtime takes it as a constructor parameter.
 *
 * <p>The application is one entity, {@code <origin>/auth/saml/metadata}, with one assertion consumer
 * for every provider: a response is matched to its provider by {@code Issuer}, as a token by
 * {@code iss}. SAML is not in the per-request chain: a request outside {@code /auth/saml} costs
 * nothing, and a signed-in user is an ordinary session.
 */
@Slf4j
public final class Saml implements SessionLogout, Bound, AutoCloseable {

    static final String FLOW = "flash_saml";
    private static final String BEARER = "urn:oasis:names:tc:SAML:2.0:cm:bearer";
    private static final String SUCCESS = "urn:oasis:names:tc:SAML:2.0:status:Success";
    private static final String PAIRWISE_ID = "urn:oasis:names:tc:SAML:attribute:pairwise-id";
    private static final String SUBJECT_ID = "urn:oasis:names:tc:SAML:attribute:subject-id";
    private static final String REFUSED = "Sign-in could not be completed; start again";
    /** The longest a bearer confirmation may be valid: providers give minutes. */
    private static final Duration MAX_CONFIRMATION = Duration.ofHours(1);
    /** How long after it was issued a logout message is still taken. */
    private static final Duration MESSAGE_LIFETIME = Duration.ofMinutes(5);

    /** Everything IIP-ALG01 to ALG06 require; SHA-1 and RSA PKCS#1 v1.5 key transport are left out (IIP-ALG08). */
    static final Set<String> ALGORITHMS = Set.of(
            Xml.SHA256, "http://www.w3.org/2001/04/xmldsig-more#sha384", "http://www.w3.org/2001/04/xmlenc#sha512",
            Xml.RSA_SHA256, "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384", "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256", "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512",
            Xml.AES128_GCM, "http://www.w3.org/2009/xmlenc11#aes192-gcm", Xml.AES256_GCM,
            "http://www.w3.org/2001/04/xmlenc#aes128-cbc", "http://www.w3.org/2001/04/xmlenc#aes192-cbc", "http://www.w3.org/2001/04/xmlenc#aes256-cbc",
            Xml.RSA_OAEP_MGF1P, Xml.RSA_OAEP);

    private final SamlKeys keys;
    final SamlIdp[] configured;
    private final Map<String, IdentityProvider> byId = new ConcurrentHashMap<>();
    private final Map<String, IdentityProvider> byEntityId = new ConcurrentHashMap<>();
    /** Assertions consumed, by issuer and ID, until they expire (SAML Profiles 4.1.4.5). */
    private final Map<String, Instant> consumed = new ConcurrentHashMap<>();
    private volatile long sweptAt;
    /** Providers whose metadata is being fetched again, so a slow one is not fetched twice at once. */
    private final Set<String> refreshing = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;
    private Security security;
    private String entityId;
    private String acs;
    private String slo;
    private byte[] metadata;
    private Thread refresher;

    // Set by SamlModule, before the app starts.
    Duration clockSkew = Duration.ofMinutes(3);
    Set<String> algorithms = ALGORITHMS;
    boolean localProviders;
    String displayName;
    String privacyStatementUrl;
    String technicalContact;
    String logo;

    Saml(SamlKeys keys, SamlIdp... providers) {
        this.keys = keys;
        this.configured = providers;
    }

    /**
     * Trusts another provider from now on: an organization connecting its own. Its metadata is fetched
     * here, so a bad provider fails this call. It serves {@code /auth/saml/{id}/login}, but is not listed
     * at {@code /auth/methods}: which provider a user signs in with is the application's call.
     *
     * @throws IllegalArgumentException the metadata is not over https on a public address, does not
     *                                  describe a usable provider, or its id or entity ID is another provider's
     * @throws IllegalStateException    the metadata could not be fetched or read
     */
    public void register(SamlIdp provider) {
        for (SamlIdp fixed : configured) {
            if (fixed.id.equals(provider.id)) throw new IllegalArgumentException("A configured provider already uses the id " + provider.id);
        }
        put(new IdentityProvider(provider, !localProviders, algorithms));
    }

    /** Stops trusting a provider: its responses are refused, its users' logouts stay local. */
    public synchronized void unregister(String id) {
        IdentityProvider removed = byId.remove(id);
        if (removed != null) byEntityId.remove(removed.entityId, removed);
    }

    /**
     * Reads every configured provider's metadata, failing the boot with the reason, and encodes this
     * application's. Needs {@code SecurityModule.origin}: the entity ID, the assertion consumer and the
     * audience every assertion is checked against are built from it, never from a request's Host.
     */
    @Override
    public void bind(Services services) {
        security = services.get(Security.class);
        String origin = security.origin();
        if (origin == null) throw new IllegalStateException("SAML needs SecurityModule.origin(...): its entity ID and the audience of every assertion are built from it");
        entityId = origin + "/auth/saml/metadata";
        acs = origin + "/auth/saml/acs";
        slo = origin + "/auth/saml/slo";
        metadata = metadata().getBytes(StandardCharsets.UTF_8);
        for (SamlIdp provider : configured) put(new IdentityProvider(provider, false, algorithms));
        refresher = Thread.ofVirtual().name("saml-metadata").start(this::refreshEvery5Minutes);
    }

    @Override
    public void close() {
        closed = true;
        if (refresher != null) refresher.interrupt();
    }

    @Override
    public Class<? extends Principal> type() {
        return SamlPrincipal.class;
    }

    // -- Sign-in ----------------------------------------------------------------

    Object metadata(Request req, Response res) {
        res.type("application/samlmetadata+xml");
        return metadata;
    }

    Object login(Request req, Response res) throws Exception {
        return start(provider(byId.get(req.param("idp"))), req, res);
    }

    /** The return of an identity provider discovery service: the provider is named by entity ID (IIP-SP04). */
    Object discovered(Request req, Response res) throws Exception {
        String chosen = req.query("entityID");
        return start(provider(chosen == null ? null : byEntityId.get(chosen)), req, res);
    }

    /**
     * Sends the browser to sign in with an {@code AuthnRequest}, signed, by HTTP-Redirect when the
     * provider has it (SAML2Int SDP-SP02), by HTTP-POST otherwise. Its ID and the path to return to
     * stay in the browser, in the {@code flash_saml} cookie the response must come back with.
     */
    private Object start(IdentityProvider idp, Request req, Response res) throws Exception {
        String id = "_" + Tokens.random(16);
        boolean https = acs.startsWith("https");
        // The response is a cross-site POST, which a Lax cookie does not ride; over http in development
        // provider and application are same-site on localhost, and None would need Secure.
        res.header("Set-Cookie", FLOW + "=" + id + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(
                Redirects.localPath(req.query("redirect")).getBytes(StandardCharsets.UTF_8))
                + "; Path=/auth/saml; Max-Age=600; HttpOnly; " + (https ? "SameSite=None; Secure" : "SameSite=Lax"));
        StringBuilder request = new StringBuilder(640)
                .append("<samlp:AuthnRequest xmlns:samlp=\"").append(SAMLP).append("\" xmlns:saml=\"").append(SAML)
                .append("\" ID=\"").append(id).append("\" Version=\"2.0\" IssueInstant=\"").append(now())
                .append("\" Destination=\"").append(escape(idp.sso)).append("\" AssertionConsumerServiceURL=\"").append(escape(acs))
                .append("\" ProtocolBinding=\"").append(IdentityProvider.POST).append("\"><saml:Issuer>").append(escape(entityId)).append("</saml:Issuer>");
        if (!idp.config.authnContext.isEmpty()) {
            request.append("<samlp:RequestedAuthnContext Comparison=\"exact\">");
            for (String classRef : idp.config.authnContext) request.append("<saml:AuthnContextClassRef>").append(escape(classRef)).append("</saml:AuthnContextClassRef>");
            request.append("</samlp:RequestedAuthnContext>");
        }
        String xml = request.append("</samlp:AuthnRequest>").toString();
        if (idp.ssoByPost) {
            res.type(ContentType.TEXT_HTML);
            return Bindings.post(idp.sso, "SAMLRequest", Xml.sign(xml, id, keys.signing()));
        }
        res.redirect(Bindings.redirect(idp.sso, "SAMLRequest", xml, null, keys.signing()));
        return null;
    }

    /**
     * The assertion consumer: a {@code Response} by HTTP-POST becomes a session. Every refusal is the
     * same 400 for the browser, so none tells an attacker more than another; the log says why.
     */
    Object consume(Request req, Response res) {
        String cookie = req.cookie(FLOW);
        int dot = cookie == null ? -1 : cookie.indexOf('.');
        res.header("Set-Cookie", FLOW + "=; Path=/auth/saml; Max-Age=0");
        QueryParams form = req.form();
        String encoded = form.get("SAMLResponse");
        if (encoded == null) throw HttpException.badRequest("No SAMLResponse");
        SignedIn in;
        try {
            in = signIn(Base64.getMimeDecoder().decode(encoded), dot < 0 ? null : cookie.substring(0, dot));
        } catch (Refused refused) {
            throw HttpException.badRequest(refused.getMessage());
        } catch (Exception invalid) {
            log.warn("SAML response refused: {}", invalid.getMessage());
            throw HttpException.badRequest(REFUSED);
        }
        // The provider's SessionNotOnOrAfter ends the session sooner, never later than the application's own timeout.
        Instant timeout = Instant.now().plus(security.sessionTimeout());
        String target = in.solicited ? returnPath(cookie.substring(dot + 1)) : form.get("RelayState");
        security.signIn(req, res, in.principal, in.expiresAt != null && in.expiresAt.isBefore(timeout) ? in.expiresAt : timeout);
        res.status(303).header("Location", Redirects.localPath(target));
        return null;
    }

    /** The path the cookie carries, or the root when the browser altered it. */
    private static String returnPath(String encoded) {
        try {
            return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException altered) {
            return "/";
        }
    }

    private record SignedIn(SamlPrincipal principal, Instant expiresAt, boolean solicited) {}

    /** A provider's answer that is not a sign-in: shown as it is, since it is no secret (SAML2Int SDP-SP11). */
    private static final class Refused extends Exception {
        Refused(String message) {
            super(message, null, false, false);
        }
    }

    /**
     * Checks a response in the order SAML Core and Profiles 4.1.4 put it, against the provider its
     * {@code Issuer} names, and reads the user from the very elements whose signatures it verified.
     *
     * @param requestId the {@code AuthnRequest} this browser sent, or {@code null}
     */
    private SignedIn signIn(byte[] message, String requestId) throws Exception {
        Element response = Xml.parse(message).getDocumentElement();
        if (!Xml.is(response, SAMLP, "Response") || !"2.0".equals(attribute(response, "Version"))) throw new IllegalArgumentException("Not a SAML 2.0 Response");
        Element status = required(required(response, SAMLP, "Status"), SAMLP, "StatusCode");
        if (!SUCCESS.equals(attribute(status, "Value"))) {
            Element second = child(status, SAMLP, "StatusCode");
            String code = attribute(second != null ? second : status, "Value");
            // Unsigned and posted by anyone: shown only when it is a status SAML defines, never as free text.
            if (code == null || !code.startsWith("urn:oasis:names:tc:SAML:2.0:status:")) throw new IllegalArgumentException("Status " + code);
            throw new Refused("The identity provider did not sign you in: " + code);
        }
        String destination = attribute(response, "Destination");
        if (destination != null && !destination.equals(acs)) throw new IllegalArgumentException("Response for " + destination);

        // 4.1.4.2: a response that is signed or carries an encrypted assertion names its issuer.
        List<Element> plain = children(response, SAML, "Assertion");
        List<Element> encrypted = children(response, SAML, "EncryptedAssertion");
        if (plain.size() + encrypted.size() != 1) throw new IllegalArgumentException("A response carries exactly one assertion here, not " + (plain.size() + encrypted.size()));
        String issuer = text(child(response, SAML, "Issuer"));
        if (issuer == null && plain.size() == 1) issuer = text(child(plain.getFirst(), SAML, "Issuer"));
        IdentityProvider idp = issuer == null ? null : byEntityId.get(issuer);
        if (idp == null || !idp.valid()) throw new IllegalArgumentException("Unknown identity provider " + issuer);

        boolean signedResponse = Xml.verify(response, idp.keys, algorithms);
        if (!signedResponse && idp.config.signedResponses) throw new IllegalArgumentException("The response is not signed");
        if (signedResponse && destination == null) throw new IllegalArgumentException("A signed response names its destination"); // Bindings 3.5.5.2
        String inResponseTo = attribute(response, "InResponseTo");
        if (inResponseTo != null && !inResponseTo.equals(requestId)) throw new IllegalArgumentException("The response answers another request");

        Element assertion = plain.isEmpty() ? Xml.decrypt(encrypted.getFirst(), keys.privateKeys, algorithms) : plain.getFirst();
        if (!Xml.is(assertion, SAML, "Assertion") || !"2.0".equals(attribute(assertion, "Version"))) throw new IllegalArgumentException("Not a SAML 2.0 Assertion");
        if (!idp.entityId.equals(text(required(assertion, SAML, "Issuer")))) throw new IllegalArgumentException("The assertion's issuer is not the response's");
        if (!Xml.verify(assertion, idp.keys, algorithms) && !signedResponse) throw new IllegalArgumentException("Neither the response nor the assertion is signed");

        Instant now = Instant.now();
        Element subject = required(assertion, SAML, "Subject");
        Instant until = null;
        boolean solicited = false;
        for (Element confirmation : children(subject, SAML, "SubjectConfirmation")) {
            Element data = child(confirmation, SAML, "SubjectConfirmationData");
            Instant notOnOrAfter = data == null ? null : instant(data, "NotOnOrAfter");
            String answers = data == null ? null : attribute(data, "InResponseTo");
            // Each one is remembered until it expires: one valid for longer is refused, or a provider could fill the memory.
            if (!BEARER.equals(attribute(confirmation, "Method")) || notOnOrAfter == null || !acs.equals(attribute(data, "Recipient"))
                    || notOnOrAfter.isAfter(now.plus(MAX_CONFIRMATION))
                    || attribute(data, "NotBefore") != null || !now.isBefore(notOnOrAfter.plus(clockSkew))
                    || (answers != null ? !answers.equals(requestId) : !idp.config.unsolicited)) continue;
            until = notOnOrAfter;
            solicited = answers != null;
            break;
        }
        if (until == null) throw new IllegalArgumentException("No bearer confirmation for " + acs + " that answers this browser's request and is still valid");
        conditions(required(assertion, SAML, "Conditions"), now);

        List<Element> statements = children(assertion, SAML, "AuthnStatement");
        if (statements.isEmpty()) throw new IllegalArgumentException("The assertion has no AuthnStatement");
        Element authn = statements.getFirst();
        if (!idp.config.authnContext.isEmpty()) {
            Element context = child(authn, SAML, "AuthnContext");
            String classRef = context == null ? null : text(child(context, SAML, "AuthnContextClassRef"));
            if (!idp.config.authnContext.contains(classRef)) throw new IllegalArgumentException("Authentication context " + classRef + " was not asked for");
        }

        Map<String, List<String>> attributes = attributes(assertion);
        SamlPrincipal.NameId nameId = nameId(subject);
        String name = identifier(attributes, PAIRWISE_ID, idp);
        if (name == null) name = identifier(attributes, SUBJECT_ID, idp);
        if (name == null && nameId != null) name = nameId.value();
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("The assertion identifies no one: no pairwise-id, subject-id or NameID");

        String id = attribute(assertion, "ID");
        if (id == null) throw new IllegalArgumentException("The assertion has no ID");
        // ponytail: consumed IDs are per instance; behind a load balancer, a replay reaching another instance within the assertion's lifetime is not caught. A shared store when there are replicas.
        if (now.toEpochMilli() - sweptAt > 60_000) {
            sweptAt = now.toEpochMilli();
            consumed.values().removeIf(expiry -> expiry.isBefore(now));
        }
        if (consumed.putIfAbsent(idp.entityId + " " + id, until.plus(clockSkew)) != null) throw new IllegalArgumentException("Assertion replayed");

        SamlPrincipal principal = new SamlPrincipal(idp.config.id, idp.entityId, name, nameId, attribute(authn, "SessionIndex"), attributes);
        return new SignedIn(principal, instant(authn, "SessionNotOnOrAfter"), solicited);
    }

    /**
     * The validity window, and an audience restriction naming this application in each restriction
     * (Core 2.5.1.4). A condition this application does not understand makes the assertion invalid.
     */
    private void conditions(Element conditions, Instant now) {
        Instant notBefore = instant(conditions, "NotBefore");
        Instant notOnOrAfter = instant(conditions, "NotOnOrAfter");
        if (notBefore != null && now.plus(clockSkew).isBefore(notBefore)) throw new IllegalArgumentException("The assertion is not valid before " + notBefore);
        if (notOnOrAfter != null && !now.isBefore(notOnOrAfter.plus(clockSkew))) throw new IllegalArgumentException("The assertion expired " + notOnOrAfter);
        boolean audience = false;
        for (org.w3c.dom.Node n = conditions.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element condition)) continue;
            if (Xml.is(condition, SAML, "AudienceRestriction")) {
                boolean named = false;
                for (Element value : children(condition, SAML, "Audience")) named |= entityId.equals(text(value));
                if (!named) throw new IllegalArgumentException("The assertion is for another audience");
                audience = true;
            } else if (!Xml.is(condition, SAML, "OneTimeUse") && !Xml.is(condition, SAML, "ProxyRestriction")) {
                throw new IllegalArgumentException("Unknown condition " + condition.getLocalName());
            }
        }
        if (!audience) throw new IllegalArgumentException("The assertion names no audience"); // Profiles 4.1.4.2
    }

    /** Every attribute by {@code Name}; {@code FriendlyName} is only a label (IIP-SP11), and a nil value is no value. */
    private static Map<String, List<String>> attributes(Element assertion) {
        Map<String, List<String>> attributes = new LinkedHashMap<>();
        for (Element statement : children(assertion, SAML, "AttributeStatement")) {
            for (Element attribute : children(statement, SAML, "Attribute")) {
                List<String> values = attributes.computeIfAbsent(attribute.getAttributeNS(null, "Name"), name -> new ArrayList<>(1));
                for (Element value : children(attribute, SAML, "AttributeValue")) {
                    String nil = value.getAttributeNS("http://www.w3.org/2001/XMLSchema-instance", "nil");
                    if (!"true".equals(nil) && !"1".equals(nil)) values.add(text(value));
                }
            }
        }
        attributes.replaceAll((name, values) -> List.copyOf(values));
        return Map.copyOf(attributes);
    }

    /**
     * A {@code pairwise-id} or {@code subject-id}, whose scope, the part after {@code @}, must be one the
     * provider's metadata declares literally when it declares any (SAML2Int SDP-SP16, SP17).
     */
    private static String identifier(Map<String, List<String>> attributes, String name, IdentityProvider idp) {
        List<String> values = attributes.get(name);
        if (values == null || values.size() != 1) return null;
        String value = values.getFirst();
        int at = value.lastIndexOf('@');
        if (at <= 0 || at == value.length() - 1) throw new IllegalArgumentException(name + " has no scope: " + value);
        if (idp.scopes != null && !idp.scopes.contains(value.substring(at + 1))) throw new IllegalArgumentException(name + " has a scope " + idp.config.id + " may not assert: " + value);
        return value;
    }

    private SamlPrincipal.NameId nameId(Element subject) throws Exception {
        Element encrypted = child(subject, SAML, "EncryptedID");
        Element nameId = encrypted != null ? Xml.decrypt(encrypted, keys.privateKeys, algorithms) : child(subject, SAML, "NameID");
        if (nameId == null) return null;
        if (!Xml.is(nameId, SAML, "NameID")) throw new IllegalArgumentException("The EncryptedID holds a " + nameId.getLocalName() + ", not a NameID");
        return new SamlPrincipal.NameId(text(nameId), attribute(nameId, "Format"), attribute(nameId, "NameQualifier"),
                attribute(nameId, "SPNameQualifier"), attribute(nameId, "SPProvidedID"));
    }

    // -- Logout -------------------------------------------------------------------

    /**
     * A {@code LogoutRequest} for the provider's session, signed and dated now, sent once the local
     * session is gone (SAML2Int SDP-SP28, SP29, SP34). {@code null} when the provider has no logout
     * service or the user no {@code NameID} to name.
     */
    @Override
    public String logoutUrl(Session ended, Request req) {
        SamlPrincipal principal = (SamlPrincipal) ended.principal();
        // By the issuer that signed the user in: an id unregistered and reused for another provider must not get the user's NameID.
        IdentityProvider idp = byEntityId.get(principal.issuer());
        if (idp == null || idp.slo == null || principal.nameId() == null) return null;
        StringBuilder xml = new StringBuilder(640)
                .append("<samlp:LogoutRequest xmlns:samlp=\"").append(SAMLP).append("\" xmlns:saml=\"").append(SAML)
                .append("\" ID=\"_").append(Tokens.random(16)).append("\" Version=\"2.0\" IssueInstant=\"").append(now())
                .append("\" Destination=\"").append(escape(idp.slo)).append("\"><saml:Issuer>").append(escape(entityId)).append("</saml:Issuer>");
        nameId(xml, principal.nameId());
        if (principal.sessionIndex() != null) xml.append("<samlp:SessionIndex>").append(escape(principal.sessionIndex())).append("</samlp:SessionIndex>");
        try {
            return Bindings.redirect(idp.slo, "SAMLRequest", xml.append("</samlp:LogoutRequest>").toString(), null, keys.signing());
        } catch (Exception unsignable) {
            throw new IllegalStateException(unsignable);
        }
    }

    /**
     * Single Logout by HTTP-Redirect, signed both ways: a provider's {@code LogoutRequest} ends this
     * browser's session when it names its user, and is answered; its {@code LogoutResponse} to ours ends the
     * round trip at {@code /}.
     */
    Object logout(Request req, Response res) throws Exception {
        boolean request = req.query("SAMLRequest") != null;
        String parameter = request ? "SAMLRequest" : "SAMLResponse";
        if (req.query(parameter) == null) throw HttpException.badRequest("No SAMLRequest or SAMLResponse");
        IdentityProvider idp;
        Element message;
        boolean completed;
        try {
            // The issuer picks the keys the signature must verify with; nothing else is read before it does.
            byte[] unverified = Bindings.inflate(req, parameter);
            message = Xml.parse(unverified).getDocumentElement();
            idp = byEntityId.get(text(required(message, SAML, "Issuer")));
            if (idp == null || !idp.valid()) throw new IllegalArgumentException("Unknown identity provider");
            Bindings.verify(req, parameter, idp.keys, algorithms);
            if (!Xml.is(message, SAMLP, request ? "LogoutRequest" : "LogoutResponse") || !slo.equals(attribute(message, "Destination"))) {
                throw new IllegalArgumentException("Not a logout message for " + slo);
            }
            // A logout message has no single use of its own: a fresh IssueInstant keeps a captured one from being replayed later.
            if (attribute(message, "ID") == null) throw new IllegalArgumentException("No ID");
            Instant now = Instant.now(), issued = instant(message, "IssueInstant"), notOnOrAfter = instant(message, "NotOnOrAfter");
            if (issued == null || issued.isAfter(now.plus(clockSkew)) || issued.isBefore(now.minus(MESSAGE_LIFETIME).minus(clockSkew))) throw new IllegalArgumentException("Issued at " + issued);
            if (notOnOrAfter != null && !now.isBefore(notOnOrAfter.plus(clockSkew))) throw new IllegalArgumentException("Expired");
            completed = request || SUCCESS.equals(attribute(required(required(message, SAMLP, "Status"), SAMLP, "StatusCode"), "Value"));
        } catch (Exception invalid) {
            log.warn("SAML logout refused: {}", invalid.getMessage());
            throw HttpException.badRequest("Logout could not be completed");
        }
        if (!request) {
            // The local session ended already; a provider that could not end its own says so, and so does this answer.
            if (!completed) throw HttpException.badRequest("The identity provider did not complete the logout");
            res.redirect("/");
            return null;
        }
        SamlPrincipal.NameId named = nameId(message);
        SamlPrincipal current = current(req);
        List<String> indexes = new ArrayList<>(1);
        for (Element index : children(message, SAMLP, "SessionIndex")) indexes.add(text(index));
        if (current != null && named != null && current.issuer().equals(idp.entityId) && current.nameId() != null
                && current.nameId().value().equals(named.value()) && (indexes.isEmpty() || indexes.contains(current.sessionIndex()))) {
            security.endSession(req, res);
        }
        String reply = "<samlp:LogoutResponse xmlns:samlp=\"" + SAMLP + "\" xmlns:saml=\"" + SAML + "\" ID=\"_" + Tokens.random(16)
                + "\" Version=\"2.0\" IssueInstant=\"" + now() + "\" Destination=\"" + escape(idp.sloResponse)
                + "\" InResponseTo=\"" + escape(attribute(message, "ID")) + "\"><saml:Issuer>" + escape(entityId)
                + "</saml:Issuer><samlp:Status><samlp:StatusCode Value=\"" + SUCCESS + "\"/></samlp:Status></samlp:LogoutResponse>";
        res.redirect(Bindings.redirect(idp.sloResponse, "SAMLResponse", reply, req.query("RelayState"), keys.signing()));
        return null;
    }

    private SamlPrincipal current(Request req) {
        try {
            SecurityIdentity identity = security.authenticate(req);
            return identity == null ? null : identity.principal(SamlPrincipal.class);
        } catch (AuthenticationFailedException rejected) {
            return null;
        }
    }

    private static void nameId(StringBuilder xml, SamlPrincipal.NameId nameId) {
        xml.append("<saml:NameID");
        qualifier(xml, "Format", nameId.format());
        qualifier(xml, "NameQualifier", nameId.nameQualifier());
        qualifier(xml, "SPNameQualifier", nameId.spNameQualifier());
        qualifier(xml, "SPProvidedID", nameId.spProvidedId());
        xml.append('>').append(escape(nameId.value())).append("</saml:NameID>");
    }

    private static void qualifier(StringBuilder xml, String name, String value) {
        if (value != null) xml.append(' ').append(name).append("=\"").append(escape(value)).append('"');
    }

    // -- Providers and metadata -----------------------------------------------------

    /** One provider per entity ID: the issuer of a response must name exactly one set of keys. */
    private synchronized void put(IdentityProvider idp) {
        IdentityProvider other = byEntityId.get(idp.entityId);
        if (other != null && !other.config.id.equals(idp.config.id)) {
            throw new IllegalArgumentException("SAML identity provider " + idp.config.id + ": " + idp.entityId + " is already " + other.config.id);
        }
        IdentityProvider replaced = byId.put(idp.config.id, idp);
        if (replaced != null && !replaced.entityId.equals(idp.entityId)) byEntityId.remove(replaced.entityId, replaced);
        byEntityId.put(idp.entityId, idp);
    }

    private static IdentityProvider provider(IdentityProvider idp) {
        if (idp == null || !idp.valid()) throw HttpException.notFound("SAML identity provider");
        return idp;
    }

    /**
     * Re-reads fetched metadata once it is an hour old or about to expire (IIP-MD02), each provider on a
     * thread of its own so a slow one holds up no other. A provider that cannot be read keeps its last
     * metadata until that expires, and is retried every five minutes.
     */
    private void refreshEvery5Minutes() {
        while (!closed) {
            try {
                Thread.sleep(Duration.ofMinutes(5));
            } catch (InterruptedException stopping) {
                return;
            }
            for (IdentityProvider idp : byId.values()) {
                if (idp.config.metadataUrl == null || Instant.now().isBefore(idp.refreshAt) || !refreshing.add(idp.config.id)) continue;
                Thread.ofVirtual().name("saml-metadata-" + idp.config.id).start(() -> {
                    try {
                        refresh(idp, new IdentityProvider(idp.config, idp.guarded, algorithms));
                    } catch (Throwable failed) {
                        log.warn("SAML metadata refresh failed for {}: {}", idp.config.id, failed.toString());
                    } finally {
                        refreshing.remove(idp.config.id);
                    }
                });
            }
        }
    }

    /** {@code fresh} in place of {@code stale}, unless the provider was unregistered or replaced meanwhile. */
    private synchronized void refresh(IdentityProvider stale, IdentityProvider fresh) {
        if (!closed && byId.get(stale.config.id) == stale) put(fresh);
    }

    /**
     * This application's metadata (SAML2Int SDP-SP39): its keys, its endpoints, the algorithms it
     * supports, that it takes any subject identifier, and what {@link SamlModule#info} gave.
     */
    private String metadata() {
        StringBuilder xml = new StringBuilder(4096)
                .append("<md:EntityDescriptor xmlns:md=\"").append(Xml.MD).append("\" xmlns:ds=\"").append(Xml.DS)
                .append("\" entityID=\"").append(escape(entityId)).append("\"><md:Extensions>")
                .append("<mdattr:EntityAttributes xmlns:mdattr=\"urn:oasis:names:tc:SAML:metadata:attribute\">")
                .append("<saml:Attribute xmlns:saml=\"").append(SAML).append("\" Name=\"urn:oasis:names:tc:SAML:profiles:subject-id:req\"")
                .append(" NameFormat=\"urn:oasis:names:tc:SAML:2.0:attrname-format:uri\"><saml:AttributeValue>any</saml:AttributeValue></saml:Attribute>")
                .append("</mdattr:EntityAttributes>");
        // Every digest and signature algorithm accepted, for a provider that picks from them (IIP-MD09)
        for (String algorithm : algorithms) {
            String element = algorithm.matches(".*#sha\\d+$") ? "DigestMethod" : Bindings.signs(algorithm) ? "SigningMethod" : null;
            if (element != null) xml.append("<alg:").append(element).append(" xmlns:alg=\"urn:oasis:names:tc:SAML:metadata:algsupport\" Algorithm=\"").append(algorithm).append("\"/>");
        }
        xml.append("</md:Extensions><md:SPSSODescriptor protocolSupportEnumeration=\"urn:oasis:names:tc:SAML:2.0:protocol\"")
                .append(" AuthnRequestsSigned=\"true\" WantAssertionsSigned=\"true\">");
        if (displayName != null || logo != null || privacyStatementUrl != null) {
            xml.append("<md:Extensions><mdui:UIInfo xmlns:mdui=\"urn:oasis:names:tc:SAML:metadata:ui\">");
            if (displayName != null) xml.append("<mdui:DisplayName xml:lang=\"en\">").append(escape(displayName)).append("</mdui:DisplayName>");
            if (logo != null) xml.append(logo);
            if (privacyStatementUrl != null) xml.append("<mdui:PrivacyStatementURL xml:lang=\"en\">").append(escape(privacyStatementUrl)).append("</mdui:PrivacyStatementURL>");
            xml.append("</mdui:UIInfo></md:Extensions>");
        }
        X509Certificate current = keys.certificate;
        key(xml, "signing", current, "");
        // What to encrypt with, GCM first (IIP-MD09): a provider that reads it never picks the broken CBC.
        key(xml, "encryption", current, "<md:EncryptionMethod Algorithm=\"" + Xml.AES256_GCM + "\"/><md:EncryptionMethod Algorithm=\""
                + Xml.AES128_GCM + "\"/><md:EncryptionMethod Algorithm=\"" + Xml.RSA_OAEP + "\"/><md:EncryptionMethod Algorithm=\"" + Xml.RSA_OAEP_MGF1P + "\"/>");
        xml.append("<md:SingleLogoutService Binding=\"").append(IdentityProvider.REDIRECT).append("\" Location=\"").append(escape(slo)).append("\"/>")
                .append("<md:AssertionConsumerService Binding=\"").append(IdentityProvider.POST).append("\" Location=\"").append(escape(acs))
                .append("\" index=\"0\" isDefault=\"true\"/></md:SPSSODescriptor>");
        if (technicalContact != null) {
            xml.append("<md:ContactPerson contactType=\"technical\"><md:EmailAddress>mailto:").append(escape(technicalContact)).append("</md:EmailAddress></md:ContactPerson>");
        }
        return xml.append("</md:EntityDescriptor>").toString();
    }

    private static void key(StringBuilder xml, String use, X509Certificate certificate, String methods) {
        try {
            xml.append("<md:KeyDescriptor use=\"").append(use).append("\"><ds:KeyInfo><ds:X509Data><ds:X509Certificate>")
                    .append(Base64.getEncoder().encodeToString(certificate.getEncoded()))
                    .append("</ds:X509Certificate></ds:X509Data></ds:KeyInfo>").append(methods).append("</md:KeyDescriptor>");
        } catch (CertificateEncodingException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** {@code xs:dateTime} in UTC to the second, as SAML Core 1.3.3 wants it. */
    private static String now() {
        return Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
    }
}
