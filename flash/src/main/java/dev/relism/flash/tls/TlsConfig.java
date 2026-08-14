package dev.relism.flash.tls;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * Declarative TLS configuration for a {@link
 * dev.relism.flash.extension.FlashConfiguration.Listener}.
 *
 * <h3>Two ways in</h3>
 *
 * <ul>
 *   <li>{@link #keystore(Path, String)} — Flash builds the {@link SSLContext} from a PKCS12/JKS
 *       keystore. A keystore holding more than one certificate entry gets SNI-based selection for
 *       free (see {@link SniKeyManager}) — no per-hostname config needed. Flash also pins {@code
 *       TLSv1.2}/{@code TLSv1.3} as the enabled protocols; cipher suites are left at the JDK's own
 *       curated default, which each JDK security release keeps current — Flash does not maintain
 *       its own suite allow-list.
 *   <li>{@link #ofContext(SSLContext)} — escape hatch. The given {@link SSLContext} is used exactly
 *       as built: Flash never calls {@code setSSLParameters} on this path unless you explicitly
 *       call {@link #applicationProtocols} or {@link #clientAuth} yourself, so anything else you
 *       configured on it is 100% authoritative.
 * </ul>
 *
 * <p>{@link #clientAuth(ClientAuth)} and {@link #applicationProtocols(String...)} apply on either
 * path — they are explicit instructions through this API, not Flash-chosen defaults, so each is
 * only ever applied when called. Neither has a value by default, on either path.
 *
 * <h3>ALPN (e.g. TLS-ALPN-01 / RFC 8737)</h3>
 *
 * {@link #applicationProtocols(String...)} sets the listener's negotiable protocol list via {@link
 * SSLParameters#setApplicationProtocols}, inherited by every accepted socket exactly like {@link
 * ClientAuth} — no per-connection code needed. ALPN is resolved during {@code ClientHello}
 * processing/{@code ServerHello} production, which always precedes {@code Certificate} production —
 * so a custom {@link javax.net.ssl.X509ExtendedKeyManager} deciding which certificate to serve can
 * read the client's negotiated protocol via {@code engine.getHandshakeApplicationProtocol()} (or
 * {@code ((SSLSocket) socket).getHandshakeApplicationProtocol()}) inside {@code
 * chooseEngineServerAlias}/{@code chooseServerAlias} and it is already resolved by then.
 */
public final class TlsConfig {

  private static final String[] SECURE_PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

  /**
   * cipher suites over TLS 1.2 (the list is unchanged from RFC 7540 Appendix A, which 9113 carries
   * forward verbatim), and that it MUST support at least {@code
   * TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256}. TLS 1.3 is unaffected: none of its cipher suites (the
   * {@code TLS_AES_*}/{@code TLS_CHACHA20_*} identifiers) appear here, since TLS 1.3 removed
   * static/non-ephemeral key exchange and CBC-mode ciphers entirely — the exact property this
   * blocklist exists to enforce for TLS 1.2.
   *
   * <p>Transcribed from Go's {@code golang.org/x/net/http2} {@code isBadCipher} table
   * (BSD-licensed, itself an implementation of this exact RFC 9113 requirement, cross-checked
   * against the IANA TLS Cipher Suite registry) rather than by hand from the RFC text, for the
   * static table be transcribed from the RFC directly and verified: a transcription error in a
   * ~280-entry list is easy to make and easy to miss, and here the failure mode is silently
   * permitting a cipher suite RFC 9113 requires rejecting. Built once, in a static
   */
  private static final Set<String> TLS12_H2_BLOCKED_CIPHERS =
      Set.of(
          "TLS_DHE_DSS_EXPORT_WITH_DES40_CBC_SHA",
          "TLS_DHE_DSS_WITH_3DES_EDE_CBC_SHA",
          "TLS_DHE_DSS_WITH_AES_128_CBC_SHA",
          "TLS_DHE_DSS_WITH_AES_128_CBC_SHA256",
          "TLS_DHE_DSS_WITH_AES_256_CBC_SHA",
          "TLS_DHE_DSS_WITH_AES_256_CBC_SHA256",
          "TLS_DHE_DSS_WITH_ARIA_128_CBC_SHA256",
          "TLS_DHE_DSS_WITH_ARIA_256_CBC_SHA384",
          "TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA",
          "TLS_DHE_DSS_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA",
          "TLS_DHE_DSS_WITH_CAMELLIA_256_CBC_SHA256",
          "TLS_DHE_DSS_WITH_DES_CBC_SHA",
          "TLS_DHE_DSS_WITH_SEED_CBC_SHA",
          "TLS_DHE_PSK_WITH_3DES_EDE_CBC_SHA",
          "TLS_DHE_PSK_WITH_AES_128_CBC_SHA",
          "TLS_DHE_PSK_WITH_AES_128_CBC_SHA256",
          "TLS_DHE_PSK_WITH_AES_256_CBC_SHA",
          "TLS_DHE_PSK_WITH_AES_256_CBC_SHA384",
          "TLS_DHE_PSK_WITH_ARIA_128_CBC_SHA256",
          "TLS_DHE_PSK_WITH_ARIA_256_CBC_SHA384",
          "TLS_DHE_PSK_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_DHE_PSK_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_DHE_PSK_WITH_NULL_SHA",
          "TLS_DHE_PSK_WITH_NULL_SHA256",
          "TLS_DHE_PSK_WITH_NULL_SHA384",
          "TLS_DHE_PSK_WITH_RC4_128_SHA",
          "TLS_DHE_RSA_EXPORT_WITH_DES40_CBC_SHA",
          "TLS_DHE_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_DHE_RSA_WITH_AES_128_CBC_SHA",
          "TLS_DHE_RSA_WITH_AES_128_CBC_SHA256",
          "TLS_DHE_RSA_WITH_AES_256_CBC_SHA",
          "TLS_DHE_RSA_WITH_AES_256_CBC_SHA256",
          "TLS_DHE_RSA_WITH_ARIA_128_CBC_SHA256",
          "TLS_DHE_RSA_WITH_ARIA_256_CBC_SHA384",
          "TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA",
          "TLS_DHE_RSA_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA",
          "TLS_DHE_RSA_WITH_CAMELLIA_256_CBC_SHA256",
          "TLS_DHE_RSA_WITH_DES_CBC_SHA",
          "TLS_DHE_RSA_WITH_SEED_CBC_SHA",
          "TLS_DH_DSS_EXPORT_WITH_DES40_CBC_SHA",
          "TLS_DH_DSS_WITH_3DES_EDE_CBC_SHA",
          "TLS_DH_DSS_WITH_AES_128_CBC_SHA",
          "TLS_DH_DSS_WITH_AES_128_CBC_SHA256",
          "TLS_DH_DSS_WITH_AES_128_GCM_SHA256",
          "TLS_DH_DSS_WITH_AES_256_CBC_SHA",
          "TLS_DH_DSS_WITH_AES_256_CBC_SHA256",
          "TLS_DH_DSS_WITH_AES_256_GCM_SHA384",
          "TLS_DH_DSS_WITH_ARIA_128_CBC_SHA256",
          "TLS_DH_DSS_WITH_ARIA_128_GCM_SHA256",
          "TLS_DH_DSS_WITH_ARIA_256_CBC_SHA384",
          "TLS_DH_DSS_WITH_ARIA_256_GCM_SHA384",
          "TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA",
          "TLS_DH_DSS_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_DH_DSS_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA",
          "TLS_DH_DSS_WITH_CAMELLIA_256_CBC_SHA256",
          "TLS_DH_DSS_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_DH_DSS_WITH_DES_CBC_SHA",
          "TLS_DH_DSS_WITH_SEED_CBC_SHA",
          "TLS_DH_RSA_EXPORT_WITH_DES40_CBC_SHA",
          "TLS_DH_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_DH_RSA_WITH_AES_128_CBC_SHA",
          "TLS_DH_RSA_WITH_AES_128_CBC_SHA256",
          "TLS_DH_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_DH_RSA_WITH_AES_256_CBC_SHA",
          "TLS_DH_RSA_WITH_AES_256_CBC_SHA256",
          "TLS_DH_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_DH_RSA_WITH_ARIA_128_CBC_SHA256",
          "TLS_DH_RSA_WITH_ARIA_128_GCM_SHA256",
          "TLS_DH_RSA_WITH_ARIA_256_CBC_SHA384",
          "TLS_DH_RSA_WITH_ARIA_256_GCM_SHA384",
          "TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA",
          "TLS_DH_RSA_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_DH_RSA_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA",
          "TLS_DH_RSA_WITH_CAMELLIA_256_CBC_SHA256",
          "TLS_DH_RSA_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_DH_RSA_WITH_DES_CBC_SHA",
          "TLS_DH_RSA_WITH_SEED_CBC_SHA",
          "TLS_DH_anon_EXPORT_WITH_DES40_CBC_SHA",
          "TLS_DH_anon_EXPORT_WITH_RC4_40_MD5",
          "TLS_DH_anon_WITH_3DES_EDE_CBC_SHA",
          "TLS_DH_anon_WITH_AES_128_CBC_SHA",
          "TLS_DH_anon_WITH_AES_128_CBC_SHA256",
          "TLS_DH_anon_WITH_AES_128_GCM_SHA256",
          "TLS_DH_anon_WITH_AES_256_CBC_SHA",
          "TLS_DH_anon_WITH_AES_256_CBC_SHA256",
          "TLS_DH_anon_WITH_AES_256_GCM_SHA384",
          "TLS_DH_anon_WITH_ARIA_128_CBC_SHA256",
          "TLS_DH_anon_WITH_ARIA_128_GCM_SHA256",
          "TLS_DH_anon_WITH_ARIA_256_CBC_SHA384",
          "TLS_DH_anon_WITH_ARIA_256_GCM_SHA384",
          "TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA",
          "TLS_DH_anon_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_DH_anon_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA",
          "TLS_DH_anon_WITH_CAMELLIA_256_CBC_SHA256",
          "TLS_DH_anon_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_DH_anon_WITH_DES_CBC_SHA",
          "TLS_DH_anon_WITH_RC4_128_MD5",
          "TLS_DH_anon_WITH_SEED_CBC_SHA",
          "TLS_ECDHE_ECDSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA",
          "TLS_ECDHE_ECDSA_WITH_AES_128_CBC_SHA256",
          "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA",
          "TLS_ECDHE_ECDSA_WITH_AES_256_CBC_SHA384",
          "TLS_ECDHE_ECDSA_WITH_ARIA_128_CBC_SHA256",
          "TLS_ECDHE_ECDSA_WITH_ARIA_256_CBC_SHA384",
          "TLS_ECDHE_ECDSA_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_ECDHE_ECDSA_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_ECDHE_ECDSA_WITH_NULL_SHA",
          "TLS_ECDHE_ECDSA_WITH_RC4_128_SHA",
          "TLS_ECDHE_PSK_WITH_3DES_EDE_CBC_SHA",
          "TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA",
          "TLS_ECDHE_PSK_WITH_AES_128_CBC_SHA256",
          "TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA",
          "TLS_ECDHE_PSK_WITH_AES_256_CBC_SHA384",
          "TLS_ECDHE_PSK_WITH_ARIA_128_CBC_SHA256",
          "TLS_ECDHE_PSK_WITH_ARIA_256_CBC_SHA384",
          "TLS_ECDHE_PSK_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_ECDHE_PSK_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_ECDHE_PSK_WITH_NULL_SHA",
          "TLS_ECDHE_PSK_WITH_NULL_SHA256",
          "TLS_ECDHE_PSK_WITH_NULL_SHA384",
          "TLS_ECDHE_PSK_WITH_RC4_128_SHA",
          "TLS_ECDHE_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256",
          "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA",
          "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
          "TLS_ECDHE_RSA_WITH_ARIA_128_CBC_SHA256",
          "TLS_ECDHE_RSA_WITH_ARIA_256_CBC_SHA384",
          "TLS_ECDHE_RSA_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_ECDHE_RSA_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_ECDHE_RSA_WITH_NULL_SHA",
          "TLS_ECDHE_RSA_WITH_RC4_128_SHA",
          "TLS_ECDH_ECDSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA",
          "TLS_ECDH_ECDSA_WITH_AES_128_CBC_SHA256",
          "TLS_ECDH_ECDSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA",
          "TLS_ECDH_ECDSA_WITH_AES_256_CBC_SHA384",
          "TLS_ECDH_ECDSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDH_ECDSA_WITH_ARIA_128_CBC_SHA256",
          "TLS_ECDH_ECDSA_WITH_ARIA_128_GCM_SHA256",
          "TLS_ECDH_ECDSA_WITH_ARIA_256_CBC_SHA384",
          "TLS_ECDH_ECDSA_WITH_ARIA_256_GCM_SHA384",
          "TLS_ECDH_ECDSA_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_ECDH_ECDSA_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_ECDH_ECDSA_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_ECDH_ECDSA_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_ECDH_ECDSA_WITH_NULL_SHA",
          "TLS_ECDH_ECDSA_WITH_RC4_128_SHA",
          "TLS_ECDH_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA",
          "TLS_ECDH_RSA_WITH_AES_128_CBC_SHA256",
          "TLS_ECDH_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA",
          "TLS_ECDH_RSA_WITH_AES_256_CBC_SHA384",
          "TLS_ECDH_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_ECDH_RSA_WITH_ARIA_128_CBC_SHA256",
          "TLS_ECDH_RSA_WITH_ARIA_128_GCM_SHA256",
          "TLS_ECDH_RSA_WITH_ARIA_256_CBC_SHA384",
          "TLS_ECDH_RSA_WITH_ARIA_256_GCM_SHA384",
          "TLS_ECDH_RSA_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_ECDH_RSA_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_ECDH_RSA_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_ECDH_RSA_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_ECDH_RSA_WITH_NULL_SHA",
          "TLS_ECDH_RSA_WITH_RC4_128_SHA",
          "TLS_ECDH_anon_WITH_3DES_EDE_CBC_SHA",
          "TLS_ECDH_anon_WITH_AES_128_CBC_SHA",
          "TLS_ECDH_anon_WITH_AES_256_CBC_SHA",
          "TLS_ECDH_anon_WITH_NULL_SHA",
          "TLS_ECDH_anon_WITH_RC4_128_SHA",
          "TLS_EMPTY_RENEGOTIATION_INFO_SCSV",
          "TLS_KRB5_EXPORT_WITH_DES_CBC_40_MD5",
          "TLS_KRB5_EXPORT_WITH_DES_CBC_40_SHA",
          "TLS_KRB5_EXPORT_WITH_RC2_CBC_40_MD5",
          "TLS_KRB5_EXPORT_WITH_RC2_CBC_40_SHA",
          "TLS_KRB5_EXPORT_WITH_RC4_40_MD5",
          "TLS_KRB5_EXPORT_WITH_RC4_40_SHA",
          "TLS_KRB5_WITH_3DES_EDE_CBC_MD5",
          "TLS_KRB5_WITH_3DES_EDE_CBC_SHA",
          "TLS_KRB5_WITH_DES_CBC_MD5",
          "TLS_KRB5_WITH_DES_CBC_SHA",
          "TLS_KRB5_WITH_IDEA_CBC_MD5",
          "TLS_KRB5_WITH_IDEA_CBC_SHA",
          "TLS_KRB5_WITH_RC4_128_MD5",
          "TLS_KRB5_WITH_RC4_128_SHA",
          "TLS_NULL_WITH_NULL_NULL",
          "TLS_PSK_WITH_3DES_EDE_CBC_SHA",
          "TLS_PSK_WITH_AES_128_CBC_SHA",
          "TLS_PSK_WITH_AES_128_CBC_SHA256",
          "TLS_PSK_WITH_AES_128_CCM",
          "TLS_PSK_WITH_AES_128_CCM_8",
          "TLS_PSK_WITH_AES_128_GCM_SHA256",
          "TLS_PSK_WITH_AES_256_CBC_SHA",
          "TLS_PSK_WITH_AES_256_CBC_SHA384",
          "TLS_PSK_WITH_AES_256_CCM",
          "TLS_PSK_WITH_AES_256_CCM_8",
          "TLS_PSK_WITH_AES_256_GCM_SHA384",
          "TLS_PSK_WITH_ARIA_128_CBC_SHA256",
          "TLS_PSK_WITH_ARIA_128_GCM_SHA256",
          "TLS_PSK_WITH_ARIA_256_CBC_SHA384",
          "TLS_PSK_WITH_ARIA_256_GCM_SHA384",
          "TLS_PSK_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_PSK_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_PSK_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_PSK_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_PSK_WITH_NULL_SHA",
          "TLS_PSK_WITH_NULL_SHA256",
          "TLS_PSK_WITH_NULL_SHA384",
          "TLS_PSK_WITH_RC4_128_SHA",
          "TLS_RSA_EXPORT_WITH_DES40_CBC_SHA",
          "TLS_RSA_EXPORT_WITH_RC2_CBC_40_MD5",
          "TLS_RSA_EXPORT_WITH_RC4_40_MD5",
          "TLS_RSA_PSK_WITH_3DES_EDE_CBC_SHA",
          "TLS_RSA_PSK_WITH_AES_128_CBC_SHA",
          "TLS_RSA_PSK_WITH_AES_128_CBC_SHA256",
          "TLS_RSA_PSK_WITH_AES_128_GCM_SHA256",
          "TLS_RSA_PSK_WITH_AES_256_CBC_SHA",
          "TLS_RSA_PSK_WITH_AES_256_CBC_SHA384",
          "TLS_RSA_PSK_WITH_AES_256_GCM_SHA384",
          "TLS_RSA_PSK_WITH_ARIA_128_CBC_SHA256",
          "TLS_RSA_PSK_WITH_ARIA_128_GCM_SHA256",
          "TLS_RSA_PSK_WITH_ARIA_256_CBC_SHA384",
          "TLS_RSA_PSK_WITH_ARIA_256_GCM_SHA384",
          "TLS_RSA_PSK_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_RSA_PSK_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_RSA_PSK_WITH_CAMELLIA_256_CBC_SHA384",
          "TLS_RSA_PSK_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_RSA_PSK_WITH_NULL_SHA",
          "TLS_RSA_PSK_WITH_NULL_SHA256",
          "TLS_RSA_PSK_WITH_NULL_SHA384",
          "TLS_RSA_PSK_WITH_RC4_128_SHA",
          "TLS_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_RSA_WITH_AES_128_CBC_SHA",
          "TLS_RSA_WITH_AES_128_CBC_SHA256",
          "TLS_RSA_WITH_AES_128_CCM",
          "TLS_RSA_WITH_AES_128_CCM_8",
          "TLS_RSA_WITH_AES_128_GCM_SHA256",
          "TLS_RSA_WITH_AES_256_CBC_SHA",
          "TLS_RSA_WITH_AES_256_CBC_SHA256",
          "TLS_RSA_WITH_AES_256_CCM",
          "TLS_RSA_WITH_AES_256_CCM_8",
          "TLS_RSA_WITH_AES_256_GCM_SHA384",
          "TLS_RSA_WITH_ARIA_128_CBC_SHA256",
          "TLS_RSA_WITH_ARIA_128_GCM_SHA256",
          "TLS_RSA_WITH_ARIA_256_CBC_SHA384",
          "TLS_RSA_WITH_ARIA_256_GCM_SHA384",
          "TLS_RSA_WITH_CAMELLIA_128_CBC_SHA",
          "TLS_RSA_WITH_CAMELLIA_128_CBC_SHA256",
          "TLS_RSA_WITH_CAMELLIA_128_GCM_SHA256",
          "TLS_RSA_WITH_CAMELLIA_256_CBC_SHA",
          "TLS_RSA_WITH_CAMELLIA_256_CBC_SHA256",
          "TLS_RSA_WITH_CAMELLIA_256_GCM_SHA384",
          "TLS_RSA_WITH_DES_CBC_SHA",
          "TLS_RSA_WITH_IDEA_CBC_SHA",
          "TLS_RSA_WITH_NULL_MD5",
          "TLS_RSA_WITH_NULL_SHA",
          "TLS_RSA_WITH_NULL_SHA256",
          "TLS_RSA_WITH_RC4_128_MD5",
          "TLS_RSA_WITH_RC4_128_SHA",
          "TLS_RSA_WITH_SEED_CBC_SHA",
          "TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA",
          "TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA",
          "TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA",
          "TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA",
          "TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA",
          "TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA",
          "TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA",
          "TLS_SRP_SHA_WITH_AES_128_CBC_SHA",
          "TLS_SRP_SHA_WITH_AES_256_CBC_SHA");

  /**
   * RFC 9113 §9.2.2: an h2 endpoint MUST support this cipher suite. Not enforced (Flash cannot
   * force a peer to offer it), but documented here as the fact {@link #applyTo}'s filtering relies
   * on: filtering the blocklist above out of the JDK's default enabled set never removes this one,
   * because it was never in the blocklist to begin with.
   */
  static final String REQUIRED_H2_CIPHER_SUITE = "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256";

  private final SSLContext context;
  private final boolean hardenDefaults;
  private final ClientAuth clientAuth;
  private final String[] applicationProtocols;

  private TlsConfig(
      SSLContext context,
      boolean hardenDefaults,
      ClientAuth clientAuth,
      String[] applicationProtocols) {
    this.context = context;
    this.hardenDefaults = hardenDefaults;
    this.clientAuth = clientAuth;
    this.applicationProtocols = applicationProtocols;
  }

  /**
   * Builds an {@link SSLContext} from a PKCS12/JKS keystore — type is guessed from the file
   * extension ({@code .jks} means JKS, anything else PKCS12). The private-key password is assumed
   * equal to the store password, the common case for PKCS12.
   */
  public static TlsConfig keystore(Path path, String password) {
    try {
      KeyStore store = KeyStore.getInstance(path.toString().endsWith(".jks") ? "JKS" : "PKCS12");
      try (InputStream in = Files.newInputStream(path)) {
        store.load(in, password.toCharArray());
      }
      KeyManagerFactory kmf =
          KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
      kmf.init(store, password.toCharArray());

      KeyManager[] managers = kmf.getKeyManagers();
      for (int i = 0; i < managers.length; i++) {
        if (managers[i] instanceof X509ExtendedKeyManager x509) {
          managers[i] = new SniKeyManager(x509, store);
        }
      }

      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(managers, null, null);
      return new TlsConfig(ctx, true, ClientAuth.NONE, null);
    } catch (GeneralSecurityException | IOException e) {
      throw new IllegalArgumentException("Failed to load TLS keystore: " + path, e);
    }
  }

  /**
   * Escape hatch — see class Javadoc. Flash applies nothing to the socket beyond what you
   * explicitly call ({@link #clientAuth}/{@link #applicationProtocols}) on this instance.
   */
  public static TlsConfig ofContext(SSLContext context) {
    return new TlsConfig(context, false, ClientAuth.NONE, null);
  }

  /** Client-certificate requirement. Applies on either construction path — see class Javadoc. */
  public TlsConfig clientAuth(ClientAuth mode) {
    return new TlsConfig(context, hardenDefaults, mode, applicationProtocols);
  }

  /**
   * ALPN protocols this listener negotiates, in preference order (e.g. {@code "acme-tls/1",
   * "http/1.1"}). Applies on either construction path — see class Javadoc for how a custom {@code
   * KeyManager} observes the negotiated value.
   */
  public TlsConfig applicationProtocols(String... protocols) {
    return new TlsConfig(context, hardenDefaults, clientAuth, protocols.clone());
  }

  /** Returns this TLS configuration with HTTP/2 enabled and HTTP/1.1 retained as fallback. */
  public TlsConfig enableHttp2Alpn() {
    List<String> protocols = new ArrayList<>();
    if (applicationProtocols != null) {
      for (String protocol : applicationProtocols) {
        if (!"h2".equals(protocol) && !"http/1.1".equals(protocol)) {
          protocols.add(protocol);
        }
      }
    }
    protocols.add("h2");
    protocols.add("http/1.1");
    return new TlsConfig(context, hardenDefaults, clientAuth, protocols.toArray(String[]::new));
  }

  // ── Consumed by HttpServer at bind time — not meant for direct use ──────────

  public SSLServerSocketFactory serverSocketFactory() {
    return context.getServerSocketFactory();
  }

  public void applyTo(SSLServerSocket socket) {
    if (hardenDefaults || applicationProtocols != null) {
      SSLParameters params = socket.getSSLParameters();
      if (hardenDefaults) params.setProtocols(SECURE_PROTOCOLS);
      if (applicationProtocols != null) params.setApplicationProtocols(applicationProtocols);
      socket.setSSLParameters(params);
    }
    if (clientAuth == ClientAuth.REQUIRE) socket.setNeedClientAuth(true);
    else if (clientAuth == ClientAuth.OPTIONAL) socket.setWantClientAuth(true);

    // suite list must exclude every suite on the TLS 1.2 blocklist. TLS 1.3 suites are
    // never in that list (see TLS12_H2_BLOCKED_CIPHERS's Javadoc) so this only narrows
    // which TLS 1.2 suites remain available; TLS 1.3 is unaffected either way.
    if (negotiatesH2()) {
      String[] enabled = socket.getEnabledCipherSuites();
      List<String> filtered = new ArrayList<>(enabled.length);
      for (String suite : enabled) {
        if (!TLS12_H2_BLOCKED_CIPHERS.contains(suite)) filtered.add(suite);
      }
      socket.setEnabledCipherSuites(filtered.toArray(new String[0]));
    }
  }

  /**
   * Whether this listener's configured ALPN protocol list ({@link #applicationProtocols}) includes
   * {@code "h2"}. Lets a caller (the connection runner, for logging; {@link #applyTo} duplicating
   * the offered-protocols check.
   */
  public boolean negotiatesH2() {
    if (applicationProtocols == null) return false;
    for (String protocol : applicationProtocols) {
      if ("h2".equals(protocol)) return true;
    }
    return false;
  }
}
