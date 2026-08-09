package dev.relism.flash.tls;

import javax.net.ssl.ExtendedSSLSession;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.X509ExtendedKeyManager;

import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Wraps a keystore's default {@link X509ExtendedKeyManager} with SNI-based alias selection:
 * every alias's certificate is inspected for its Subject Alternative Names (Common Name as
 * fallback) so a keystore holding one entry per domain serves the right certificate per
 * {@code ClientHello} — no explicit hostname-to-alias mapping needed from the caller.
 *
 * <p>{@link #defaultAlias} is simply the keystore's first key entry — same convention as
 * nginx/HAProxy's {@code default_server}: used when the client sends no SNI, or an SNI name
 * that matches nothing here.
 *
 * <p>Only server-alias selection is overridden; every other {@link X509ExtendedKeyManager}
 * method (client aliases, certificate chains, private keys) delegates unchanged.
 */
final class SniKeyManager extends X509ExtendedKeyManager {

    private final X509ExtendedKeyManager delegate;
    private final Map<String, String>    aliasByHostname;
    private final String                 defaultAlias;

    SniKeyManager(X509ExtendedKeyManager delegate, KeyStore keyStore) throws KeyStoreException {
        this.delegate = delegate;

        Map<String, String> byHostname = new HashMap<>();
        String first = null;
        for (String alias : Collections.list(keyStore.aliases())) {
            if (!keyStore.isKeyEntry(alias)) continue;
            if (first == null) first = alias;
            Certificate cert = keyStore.getCertificate(alias);
            if (cert instanceof X509Certificate x509) {
                for (String host : hostnamesOf(x509)) byHostname.putIfAbsent(host, alias);
            }
        }
        this.aliasByHostname = byHostname;
        this.defaultAlias    = first;
    }

    /** DNS SANs (preferred) or, failing that, the certificate's CN — all lower-cased for matching. */
    private static List<String> hostnamesOf(X509Certificate cert) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            if (sans != null) {
                for (List<?> san : sans) {
                    if (san.get(0).equals(2)) // dNSName, see X509Certificate#getSubjectAlternativeNames
                        names.add(san.get(1).toString().toLowerCase(Locale.ROOT));
                }
            }
        } catch (CertificateParsingException ignored) {
            // Fall through to the CN below.
        }
        if (names.isEmpty()) {
            for (String part : cert.getSubjectX500Principal().getName().split(",")) {
                part = part.trim();
                if (part.regionMatches(true, 0, "CN=", 0, 3)) {
                    names.add(part.substring(3).toLowerCase(Locale.ROOT));
                    break;
                }
            }
        }
        return names;
    }

    @Override
    public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
        return resolve(engine.getHandshakeSession());
    }

    @Override
    public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
        return resolve(socket instanceof SSLSocket ssl ? ssl.getHandshakeSession() : null);
    }

    /**
     * Every TLS implementation in practice sends at most one {@code server_name} entry (RFC 6066
     * permits a list, but only the {@code host_name} type exists and clients send zero or one of
     * it), so indexing {@code names.get(0)} directly — rather than a for-each, which would
     * allocate an {@link java.util.Iterator} per handshake — is both correct and allocation-free
     * on the JDK's own {@code List.copyOf}-backed {@link SSLSession#getRequestedServerNames()},
     * deterministically rather than relying on the JIT to prove the iterator never escapes.
     */
    private String resolve(SSLSession session) {
        if (session instanceof ExtendedSSLSession ext) {
            List<SNIServerName> names = ext.getRequestedServerNames();
            if (!names.isEmpty() && names.get(0) instanceof SNIHostName host) {
                String alias = aliasByHostname.get(host.getAsciiName().toLowerCase(Locale.ROOT));
                if (alias != null) return alias;
            }
        }
        return defaultAlias;
    }

    // ── Delegated — this class only changes server-alias selection ─────────────

    @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return delegate.getClientAliases(keyType, issuers); }
    @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return delegate.chooseClientAlias(keyType, issuers, socket); }
    @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return delegate.chooseEngineClientAlias(keyType, issuers, engine); }
    @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return delegate.getServerAliases(keyType, issuers); }
    @Override public X509Certificate[] getCertificateChain(String alias) { return delegate.getCertificateChain(alias); }
    @Override public PrivateKey getPrivateKey(String alias) { return delegate.getPrivateKey(alias); }
}
