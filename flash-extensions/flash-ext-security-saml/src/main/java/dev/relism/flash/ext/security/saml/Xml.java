package dev.relism.flash.ext.security.saml;

import lombok.extern.slf4j.Slf4j;
import org.apache.xml.security.Init;
import org.apache.xml.security.c14n.Canonicalizer;
import org.apache.xml.security.encryption.EncryptedKey;
import org.apache.xml.security.encryption.XMLCipher;
import org.apache.xml.security.signature.Reference;
import org.apache.xml.security.signature.SignedInfo;
import org.apache.xml.security.signature.XMLSignature;
import org.apache.xml.security.transforms.Transforms;
import org.apache.xml.security.utils.XMLUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The XML under SAML: one hardened parse, signatures bound to the element they vouch for, and
 * decryption in place. Everything a caller reads afterwards comes from the element it verified, never
 * from a second look at the document: that second look is how signature wrapping works.
 */
@Slf4j
final class Xml {

    static final String SAMLP = "urn:oasis:names:tc:SAML:2.0:protocol";
    static final String SAML = "urn:oasis:names:tc:SAML:2.0:assertion";
    static final String MD = "urn:oasis:names:tc:SAML:2.0:metadata";
    static final String DS = "http://www.w3.org/2000/09/xmldsig#";
    static final String XENC = "http://www.w3.org/2001/04/xmlenc#";

    static final String RSA_SHA256 = "http://www.w3.org/2001/04/xmldsig-more#rsa-sha256";
    static final String SHA256 = "http://www.w3.org/2001/04/xmlenc#sha256";
    static final String AES256_GCM = "http://www.w3.org/2009/xmlenc11#aes256-gcm";
    static final String AES128_GCM = "http://www.w3.org/2009/xmlenc11#aes128-gcm";
    static final String RSA_OAEP_MGF1P = "http://www.w3.org/2001/04/xmlenc#rsa-oaep-mgf1p";
    static final String RSA_OAEP = "http://www.w3.org/2009/xmlenc11#rsa-oaep";
    static final String RSA_1_5 = "http://www.w3.org/2001/04/xmlenc#rsa-1_5";

    private static volatile boolean cbcWarned;

    /** What a SAML signature may do to the element before digesting it: drop itself, canonicalize. */
    private static final Set<String> TRANSFORMS = Set.of(Transforms.TRANSFORM_ENVELOPED_SIGNATURE,
            Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS, Canonicalizer.ALGO_ID_C14N_OMIT_COMMENTS, Canonicalizer.ALGO_ID_C14N11_OMIT_COMMENTS);

    static {
        Init.init();
    }

    private Xml() {}

    /** Namespace aware, and a document type declaration is refused: no entities, nothing fetched (IIP-G03). */
    static Document parse(byte[] xml) throws Exception {
        return XMLUtils.read(new ByteArrayInputStream(xml), true);
    }

    /** The only {@code ns:name} child of {@code parent}, or {@code null}; two of them is a malformed message. */
    static Element child(Element parent, String ns, String name) {
        Element found = null;
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && name.equals(e.getLocalName()) && ns.equals(e.getNamespaceURI())) {
                if (found != null) throw new IllegalArgumentException("More than one " + name + " in " + parent.getLocalName());
                found = e;
            }
        }
        return found;
    }

    static List<Element> children(Element parent, String ns, String name) {
        List<Element> found = new ArrayList<>(2);
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element e && name.equals(e.getLocalName()) && ns.equals(e.getNamespaceURI())) found.add(e);
        return found;
    }

    static Element required(Element parent, String ns, String name) {
        Element found = child(parent, ns, name);
        if (found == null) throw new IllegalArgumentException("No " + name + " in " + parent.getLocalName());
        return found;
    }

    /** All of the element's text, comments skipped rather than ending it: {@code a<!---->b} reads {@code ab}. */
    static String text(Element element) {
        return element == null ? null : element.getTextContent().strip();
    }

    /** The attribute, or {@code null} when it is absent; DOM answers {@code ""} for both. */
    static String attribute(Element element, String name) {
        return element.hasAttributeNS(null, name) ? element.getAttributeNS(null, name) : null;
    }

    static Instant instant(Element element, String name) {
        String value = attribute(element, name);
        return value == null ? null : Instant.parse(value);
    }

    static boolean is(Element element, String ns, String name) {
        return name.equals(element.getLocalName()) && ns.equals(element.getNamespaceURI());
    }

    /**
     * Whether {@code signed} carries an enveloped signature, which then must verify with one of
     * {@code keys}: its only reference must be {@code #} and the element's own ID, its transforms
     * only drop the signature and canonicalize, its algorithms {@code allowed} ones.
     */
    static boolean verify(Element signed, List<PublicKey> keys, Set<String> allowed) throws Exception {
        Element signature = child(signed, DS, "Signature");
        if (signature == null) return false;
        String id = attribute(signed, "ID");
        if (id == null || id.isEmpty()) throw new IllegalArgumentException(signed.getLocalName() + " is signed but has no ID");
        signed.setIdAttributeNS(null, "ID", true);
        XMLSignature xml = new XMLSignature(signature, "", true);
        SignedInfo info = xml.getSignedInfo();
        if (info.getLength() != 1) throw new IllegalArgumentException("A SAML signature has exactly one reference");
        Reference reference = info.item(0);
        if (!("#" + id).equals(reference.getURI())) throw new IllegalArgumentException("The signature does not reference " + signed.getLocalName());
        allow(allowed, info.getSignatureMethodURI());
        allow(allowed, reference.getMessageDigestAlgorithm().getAlgorithmURI());
        if (!TRANSFORMS.contains(info.getCanonicalizationMethodURI())) throw new IllegalArgumentException("Canonicalization " + info.getCanonicalizationMethodURI());
        Transforms transforms = reference.getTransforms();
        for (int i = 0; transforms != null && i < transforms.getLength(); i++) {
            if (!TRANSFORMS.contains(transforms.item(i).getURI())) throw new IllegalArgumentException("Transform " + transforms.item(i).getURI());
        }
        // Every key of the signature's family (IIP-MD07): a key of another family, met while a provider rolls
        // its algorithm over, would throw and leave this signature unable to verify with the right one.
        String family = info.getSignatureMethodURI().contains("ecdsa") ? "EC" : "RSA";
        for (PublicKey key : keys) if (key.getAlgorithm().equals(family) && xml.checkSignatureValue(key)) return true;
        throw new IllegalArgumentException("The signature of " + signed.getLocalName() + " does not verify with the issuer's keys");
    }

    /**
     * Decrypts an {@code EncryptedAssertion} or {@code EncryptedID} in place and returns what it held.
     * Each encrypted key, carried inside the data or beside it, is tried with each of {@code keys}.
     */
    static Element decrypt(Element encrypted, List<PrivateKey> keys, Set<String> allowed) throws Exception {
        Element data = required(encrypted, XENC, "EncryptedData");
        String algorithm = required(data, XENC, "EncryptionMethod").getAttributeNS(null, "Algorithm");
        allow(allowed, algorithm);
        if (algorithm.endsWith("-cbc") && !cbcWarned) {
            cbcWarned = true;
            log.warn("An identity provider encrypts with {}, which padding oracles break: configure it for AES-GCM (IIP-ALG05)", algorithm);
        }
        List<Element> wrapped = children(encrypted, XENC, "EncryptedKey");
        Element info = child(data, DS, "KeyInfo");
        if (info != null) wrapped.addAll(children(info, XENC, "EncryptedKey"));
        for (Element candidate : wrapped) {
            allow(allowed, required(candidate, XENC, "EncryptionMethod").getAttributeNS(null, "Algorithm"));
            for (PrivateKey key : keys) {
                Key secret;
                try {
                    XMLCipher unwrap = XMLCipher.getInstance();
                    unwrap.init(XMLCipher.UNWRAP_MODE, key);
                    EncryptedKey encryptedKey = unwrap.loadEncryptedKey(encrypted.getOwnerDocument(), candidate);
                    secret = unwrap.decryptKey(encryptedKey, algorithm);
                } catch (Exception notThisKey) {
                    continue;
                }
                XMLCipher cipher = XMLCipher.getInstance();
                cipher.init(XMLCipher.DECRYPT_MODE, secret);
                cipher.doFinal(encrypted.getOwnerDocument(), data);
                Element plain = null;
                for (Node n = encrypted.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n instanceof Element e && !is(e, XENC, "EncryptedKey")) {
                        if (plain != null) throw new IllegalArgumentException("More than one element encrypted in " + encrypted.getLocalName());
                        plain = e;
                    }
                }
                if (plain == null) throw new IllegalArgumentException("Nothing encrypted in " + encrypted.getLocalName());
                return plain;
            }
        }
        throw new IllegalArgumentException("No key of this application decrypts " + encrypted.getLocalName());
    }

    /** {@code xml} with an enveloped RSA-SHA256 signature over {@code id}, after its {@code Issuer}: the POST binding's. */
    static String sign(String xml, String id, PrivateKey key) throws Exception {
        Document document = parse(xml.getBytes(StandardCharsets.UTF_8));
        Element root = document.getDocumentElement();
        root.setIdAttributeNS(null, "ID", true);
        XMLSignature signature = new XMLSignature(document, "", RSA_SHA256, Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        root.insertBefore(signature.getElement(), required(root, SAML, "Issuer").getNextSibling());
        Transforms transforms = new Transforms(document);
        transforms.addTransform(Transforms.TRANSFORM_ENVELOPED_SIGNATURE);
        transforms.addTransform(Canonicalizer.ALGO_ID_C14N_EXCL_OMIT_COMMENTS);
        signature.addDocument("#" + id, transforms, SHA256);
        signature.sign(key);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLUtils.outputDOM(document, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** {@code value} safe inside an attribute or element of a template. */
    static String escape(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static void allow(Set<String> allowed, String algorithm) {
        if (!allowed.contains(algorithm)) throw new IllegalArgumentException("Algorithm not allowed: " + algorithm);
    }
}
