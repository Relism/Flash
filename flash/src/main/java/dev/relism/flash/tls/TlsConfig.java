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
 *       exactly as built: Flash never calls {@code setSSLParameters} on this path, so protocols,
 *       cipher suites, and ALPN (e.g. {@code acme-tls/1} for TLS-ALPN-01) you configured on it
 *       are 100% authoritative.</li>
 * </ul>
 *
 * <p>{@link #clientAuth(ClientAuth)} applies on either path — it is an explicit instruction
 * through this API, not a Flash-chosen default, so it is only ever applied when called. The
 * {@link ClientAuth#NONE} default makes no client-auth call at all, on either path.
 */
public final class TlsConfig {

    private static final String[] SECURE_PROTOCOLS = { "TLSv1.3", "TLSv1.2" };

    private final SSLContext context;
    private final boolean    hardenDefaults;
    private final ClientAuth clientAuth;

    private TlsConfig(SSLContext context, boolean hardenDefaults, ClientAuth clientAuth) {
        this.context        = context;
        this.hardenDefaults = hardenDefaults;
        this.clientAuth     = clientAuth;
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
            return new TlsConfig(ctx, true, ClientAuth.NONE);
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalArgumentException("Failed to load TLS keystore: " + path, e);
        }
    }

    /** Escape hatch — see class Javadoc. Flash applies nothing to the socket beyond {@link #clientAuth}. */
    public static TlsConfig ofContext(SSLContext context) {
        return new TlsConfig(context, false, ClientAuth.NONE);
    }

    /** Client-certificate requirement. Applies on either construction path — see class Javadoc. */
    public TlsConfig clientAuth(ClientAuth mode) {
        return new TlsConfig(context, hardenDefaults, mode);
    }

    // ── Consumed by HttpServer at bind time — not meant for direct use ──────────

    public SSLServerSocketFactory serverSocketFactory() {
        return context.getServerSocketFactory();
    }

    public void applyTo(SSLServerSocket socket) {
        if (hardenDefaults) {
            SSLParameters params = socket.getSSLParameters();
            params.setProtocols(SECURE_PROTOCOLS);
            socket.setSSLParameters(params);
        }
        if (clientAuth == ClientAuth.REQUIRE)       socket.setNeedClientAuth(true);
        else if (clientAuth == ClientAuth.OPTIONAL) socket.setWantClientAuth(true);
    }
}
