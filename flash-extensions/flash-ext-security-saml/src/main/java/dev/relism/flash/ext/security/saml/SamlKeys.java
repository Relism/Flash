package dev.relism.flash.ext.security.saml;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The application's keys as a SAML service provider, each a PKCS#8 private key and its X.509
 * certificate in PEM. The first signs the application's requests and is the one identity providers
 * encrypt to; every one decrypts. A key is rotated by putting its successor first and keeping it
 * until nothing encrypted to it is in flight.
 *
 * <pre>{@code
 * SamlKeys.of(Files.readString(Path.of("sp.key")), Files.readString(Path.of("sp.crt")))
 * }</pre>
 *
 * <p>RSA of at least 2048 bits (SAML2Int SDP-MD06): RSA-OAEP is the key transport every identity
 * provider supports. The certificate only carries the key, so a self-signed one is the norm.
 */
public final class SamlKeys {

    final List<PrivateKey> privateKeys;
    /** The first key's: the only one published. */
    final X509Certificate certificate;

    private SamlKeys(List<PrivateKey> privateKeys, X509Certificate certificate) {
        this.privateKeys = privateKeys;
        this.certificate = certificate;
    }

    /** @throws IllegalArgumentException the key is not RSA of 2048 bits or more, or not the certificate's */
    public static SamlKeys of(String privateKeyPem, String certificatePem) {
        return new SamlKeys(List.of(), null).and(privateKeyPem, certificatePem);
    }

    /** These keys plus one that only decrypts: the key being rotated out. */
    public SamlKeys and(String privateKeyPem, String certificatePem) {
        X509Certificate certificate;
        RSAPrivateCrtKey key;
        try {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(certificatePem.getBytes(StandardCharsets.US_ASCII)));
            key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pem(privateKeyPem)));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Not a PKCS#8 RSA private key and an X.509 certificate in PEM", invalid);
        }
        if (!(certificate.getPublicKey() instanceof RSAPublicKey rsa) || !rsa.getModulus().equals(key.getModulus())) {
            throw new IllegalArgumentException("The private key is not the certificate's");
        }
        if (rsa.getModulus().bitLength() < 2048) throw new IllegalArgumentException("An RSA key needs at least 2048 bits");
        List<PrivateKey> keys = new ArrayList<>(privateKeys);
        keys.add(key);
        return new SamlKeys(List.copyOf(keys), this.certificate != null ? this.certificate : certificate);
    }

    PrivateKey signing() {
        return privateKeys.getFirst();
    }

    private static byte[] pem(String pem) {
        return Base64.getMimeDecoder().decode(pem.replaceAll("-----[A-Z ]+-----", ""));
    }
}
