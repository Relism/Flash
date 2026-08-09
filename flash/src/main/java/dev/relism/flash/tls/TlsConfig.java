package dev.relism.flash.tls;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

/**
 * Declarative TLS configuration for a {@link dev.relism.flash.extension.FlashConfiguration.Listener}.
 *
 * <h3>Two ways in</h3>
 * <ul>
 *   <li>{@link #keystore(Path, String)} — Flash builds the {@link SSLContext} from a PKCS12/JKS
 *       keystore. A keystore holding more than one certificate entry gets SNI-based selection
 *       for free (see {@link SniKeyManager}) — no per-hostname config needed. Flash also pins
 *       {@code TLSv1.2}/{@code TLSv1.3} as the enabled protocols; cipher suites are left at the
 *       JDK's own curated default, which each JDK security release keeps current — Flash does
 *       not maintain its own suite allow-list.</li>
 *   <li>{@link #ofContext(SSLContext)} — escape hatch. The given {@link SSLContext} is used
 *       exactly as built: Flash never calls {@code setSSLParameters} on this path unless you
 *       explicitly call {@link #applicationProtocols} or {@link #clientAuth} yourself, so
 *       anything else you configured on it is 100% authoritative.</li>
 * </ul>
 *
 * <p>{@link #clientAuth(ClientAuth)} and {@link #applicationProtocols(String...)} apply on
 * either path — they are explicit instructions through this API, not Flash-chosen defaults, so
 * each is only ever applied when called. Neither has a value by default, on either path.
 *
 * <h3>ALPN (e.g. TLS-ALPN-01 / RFC 8737)</h3>
 * {@link #applicationProtocols(String...)} sets the listener's negotiable protocol list via
 * {@link SSLParameters#setApplicationProtocols}, inherited by every accepted socket exactly like
 * {@link ClientAuth} — no per-connection code needed. ALPN is resolved during {@code ClientHello}
 * processing/{@code ServerHello} production, which always precedes {@code Certificate} production
 * — so a custom {@link javax.net.ssl.X509ExtendedKeyManager} deciding which certificate to serve
 * can read the client's negotiated protocol via {@code engine.getHandshakeApplicationProtocol()}
 * (or {@code ((SSLSocket) socket).getHandshakeApplicationProtocol()}) inside
 * {@code chooseEngineServerAlias}/{@code chooseServerAlias} and it is already resolved by then.
 */
public final class TlsConfig {

    private static final String[] SECURE_PROTOCOLS = { "TLSv1.3", "TLSv1.2" };

    private final SSLContext context;
    private final boolean    hardenDefaults;
    private final ClientAuth clientAuth;
    private final String[]   applicationProtocols;

    private TlsConfig(SSLContext context, boolean hardenDefaults, ClientAuth clientAuth, String[] applicationProtocols) {
        this.context              = context;
        this.hardenDefaults       = hardenDefaults;
        this.clientAuth           = clientAuth;
        this.applicationProtocols = applicationProtocols;
    }

    /**
     * Builds an {@link SSLContext} from a PKCS12/JKS keystore — type is guessed from the file
     * extension ({@code .jks} means JKS, anything else PKCS12). The private-key password is
     * assumed equal to the store password, the common case for PKCS12.
     */
    public static TlsConfig keystore(Path path, String password) {
        try {
            KeyStore store = KeyStore.getInstance(path.toString().endsWith(".jks") ? "JKS" : "PKCS12");
            try (InputStream in = Files.newInputStream(path)) {
                store.load(in, password.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
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
     * ALPN protocols this listener negotiates, in preference order (e.g.
     * {@code "acme-tls/1", "http/1.1"}). Applies on either construction path — see class Javadoc
     * for how a custom {@code KeyManager} observes the negotiated value.
     */
    public TlsConfig applicationProtocols(String... protocols) {
        return new TlsConfig(context, hardenDefaults, clientAuth, protocols.clone());
    }

    // ── Consumed by HttpServer at bind time — not meant for direct use ──────────

    public SSLServerSocketFactory serverSocketFactory() {
        return context.getServerSocketFactory();
    }

    public void applyTo(SSLServerSocket socket) {
        if (hardenDefaults || applicationProtocols != null) {
            SSLParameters params = socket.getSSLParameters();
            if (hardenDefaults)               params.setProtocols(SECURE_PROTOCOLS);
            if (applicationProtocols != null) params.setApplicationProtocols(applicationProtocols);
            socket.setSSLParameters(params);
        }
        if (clientAuth == ClientAuth.REQUIRE)       socket.setNeedClientAuth(true);
        else if (clientAuth == ClientAuth.OPTIONAL) socket.setWantClientAuth(true);
    }
}
