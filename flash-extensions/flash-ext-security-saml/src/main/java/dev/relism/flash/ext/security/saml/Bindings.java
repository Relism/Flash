package dev.relism.flash.ext.security.saml;

import dev.relism.flash.http.Request;
import dev.relism.fpr.core.ByteView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/** How SAML messages travel through the browser: HTTP-Redirect and HTTP-POST (SAML Bindings 3.4, 3.5). */
final class Bindings {

    /** A redirect message inflates to at most this: DEFLATE turns a few kilobytes of query into gigabytes. */
    private static final int MAX_INFLATED = 256 * 1024;

    /** XML Signature algorithm URIs a Redirect signature may name, as JCA calls them. */
    private static final Map<String, String> JCA = Map.of(
            Xml.RSA_SHA256, "SHA256withRSA",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha384", "SHA384withRSA",
            "http://www.w3.org/2001/04/xmldsig-more#rsa-sha512", "SHA512withRSA",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha256", "SHA256withECDSA",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha384", "SHA384withECDSA",
            "http://www.w3.org/2001/04/xmldsig-more#ecdsa-sha512", "SHA512withECDSA");

    private Bindings() {}

    /** Whether {@code algorithm} is a signature algorithm, rather than a digest or an encryption. */
    static boolean signs(String algorithm) {
        return JCA.containsKey(algorithm);
    }

    /**
     * {@code endpoint} carrying {@code xml} as {@code parameter} through a redirect, signed with
     * {@code key} over the encoded parameters in their order (3.4.4.1).
     */
    static String redirect(String endpoint, String parameter, String xml, String relayState, PrivateKey key) throws Exception {
        Deflater deflater = new Deflater(Deflater.DEFAULT_COMPRESSION, true);
        ByteArrayOutputStream deflated = new ByteArrayOutputStream(xml.length() / 2);
        try {
            deflater.setInput(xml.getBytes(StandardCharsets.UTF_8));
            deflater.finish();
            byte[] chunk = new byte[1024];
            while (!deflater.finished()) deflated.write(chunk, 0, deflater.deflate(chunk));
        } finally {
            deflater.end();
        }
        StringBuilder query = new StringBuilder(endpoint.length() + 1024).append(parameter).append('=').append(encode(Base64.getEncoder().encodeToString(deflated.toByteArray())));
        if (relayState != null) query.append("&RelayState=").append(encode(relayState));
        query.append("&SigAlg=").append(encode(Xml.RSA_SHA256));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(query.toString().getBytes(StandardCharsets.US_ASCII));
        query.append("&Signature=").append(encode(Base64.getEncoder().encodeToString(signature.sign())));
        return query.insert(0, endpoint.indexOf('?') < 0 ? '?' : '&').insert(0, endpoint).toString();
    }

    /**
     * Throws unless the redirect's signature, required, verifies with one of {@code keys} over the
     * parameters exactly as the client sent them, not re-encoded (3.4.4.1).
     */
    static void verify(Request req, String parameter, List<PublicKey> keys, Set<String> allowed) throws Exception {
        String algorithm = req.query("SigAlg");
        String value = req.query("Signature");
        if (value == null || algorithm == null) throw new IllegalArgumentException("The " + parameter + " is not signed");
        if (!allowed.contains(algorithm) || !JCA.containsKey(algorithm)) throw new IllegalArgumentException("Algorithm not allowed: " + algorithm);
        String query = ascii(req.rawQuery());
        String relay = raw(query, "RelayState");
        byte[] content = (raw(query, parameter) + (relay != null ? "&" + relay : "") + "&" + raw(query, "SigAlg")).getBytes(StandardCharsets.US_ASCII);
        byte[] expected = Base64.getMimeDecoder().decode(value);
        // An ECDSA value comes as XML Signature writes it, r and s side by side (authentik does), or DER as JCA
        // writes it: the same signature either way, so both are read.
        String jca = JCA.get(algorithm);
        List<String> encodings = jca.endsWith("ECDSA") ? List.of(jca + "inP1363Format", jca) : List.of(jca);
        for (PublicKey key : keys) {
            for (String encoding : encodings) {
                Signature signature = Signature.getInstance(encoding);
                try {
                    signature.initVerify(key);
                    signature.update(content);
                    if (signature.verify(expected)) return;
                } catch (InvalidKeyException | java.security.SignatureException otherFamilyOrEncoding) {
                    // the next key, or the other encoding
                }
            }
        }
        throw new IllegalArgumentException("The " + parameter + " signature does not verify with the issuer's keys");
    }

    /** The {@code parameter} message of a redirect, inflated: not yet verified. */
    static byte[] inflate(Request req, String parameter) throws Exception {
        // A '+' sent unencoded reads back as a space: base64 has no spaces, so it can only have been a '+'.
        byte[] deflated = Base64.getMimeDecoder().decode(req.query(parameter).replace(' ', '+'));
        Inflater inflater = new Inflater(true);
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(deflated), inflater)) {
            byte[] xml = in.readNBytes(MAX_INFLATED + 1);
            if (xml.length > MAX_INFLATED) throw new IllegalArgumentException("The " + parameter + " inflates past " + MAX_INFLATED + " bytes");
            return xml;
        } finally {
            inflater.end(); // an Inflater handed to the stream is not ended by closing it
        }
    }

    /** A page that posts {@code xml} as {@code parameter} to {@code url} as it loads (3.5.4). */
    static String post(String url, String parameter, String xml) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>Signing in</title></head>"
                + "<body onload=\"document.forms[0].submit()\"><form method=\"post\" action=\"" + Xml.escape(url) + "\">"
                + "<input type=\"hidden\" name=\"" + parameter + "\" value=\""
                + Base64.getEncoder().encodeToString(xml.getBytes(StandardCharsets.UTF_8)) + "\">"
                + "<noscript><button type=\"submit\">Continue</button></noscript></form></body></html>";
    }

    static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** {@code name=value} as it appears in {@code query}, or {@code null}. */
    private static String raw(String query, String name) {
        for (int start = 0; start < query.length(); ) {
            int end = query.indexOf('&', start);
            if (end < 0) end = query.length();
            if (query.startsWith(name, start) && query.length() > start + name.length() && query.charAt(start + name.length()) == '=') {
                return query.substring(start, end);
            }
            start = end + 1;
        }
        return null;
    }

    private static String ascii(ByteView bytes) {
        byte[] copy = new byte[bytes.length()];
        for (int i = 0; i < copy.length; i++) copy[i] = bytes.byteAt(i);
        return new String(copy, StandardCharsets.ISO_8859_1);
    }
}
