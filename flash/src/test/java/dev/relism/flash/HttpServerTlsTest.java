package dev.relism.flash;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.tls.ClientAuth;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedKeyManager;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end TLS coverage over real sockets — same style as {@link HttpServerTest}, just with
 * an {@link SSLSocketFactory} client instead of a raw one. Certificates are generated per test
 * via {@link TestKeystores} (JDK {@code keytool}, no fixture files, no extra crypto dependency).
 */
class HttpServerTlsTest {

    private static final int SOCKET_TIMEOUT_MS = 5000;

    private FlashApp app;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static String httpGet(SSLSocket socket, String path) throws IOException {
        socket.setSoTimeout(SOCKET_TIMEOUT_MS);
        socket.startHandshake();
        OutputStream out = socket.getOutputStream();
        out.write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.flush();
        return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    // ── Basic HTTPS ──────────────────────────────────────────────────────────

    @Test
    void httpsRequest_servedOverModernTls(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "id.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        SSLSocketFactory factory = TestKeystores.trustAllClientContext().getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", port)) {
            String response = httpGet(socket, "/ping");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.endsWith("pong"));
            assertTrue(java.util.List.of("TLSv1.2", "TLSv1.3").contains(socket.getSession().getProtocol()));
        }
    }

    // ── SNI: one keystore, two domains, two certificates ────────────────────

    @Test
    void sni_servesCertificateMatchingRequestedHostname(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "sni.p12", "changeit",
                TestKeystores.Entry.of("a", "a.test"),
                TestKeystores.Entry.of("b", "b.test"));
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        assertEquals("a.test", peerCn(port, "a.test"));
        assertEquals("b.test", peerCn(port, "b.test"));
        // No/unknown SNI falls back to the first keystore entry ("a") — same convention as
        // nginx/HAProxy's default_server.
        assertEquals("a.test", peerCn(port, null));
    }

    private static String peerCn(int port, String sniHostname) throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        try (SSLSocket socket = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port)) {
            if (sniHostname != null) {
                SSLParameters params = socket.getSSLParameters();
                params.setServerNames(java.util.List.of(new SNIHostName(sniHostname)));
                socket.setSSLParameters(params);
            }
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.startHandshake();
            X509Certificate cert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
            String dn = cert.getSubjectX500Principal().getName();
            for (String part : dn.split(",")) {
                part = part.trim();
                if (part.regionMatches(true, 0, "CN=", 0, 3)) return part.substring(3);
            }
            return null;
        }
    }

    // ── mTLS ─────────────────────────────────────────────────────────────────

    @Test
    void mTls_requireRejectsClientWithNoCertificate(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "id.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit").clientAuth(ClientAuth.REQUIRE))
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        SSLSocketFactory factory = TestKeystores.trustAllClientContext().getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", port)) {
            // TLS 1.3 validates the (here: empty) client certificate chain only after the
            // client's Finished message — startHandshake() alone can return cleanly from the
            // client's point of view. The server's fatal alert only surfaces on the next I/O,
            // so the round trip (not the handshake call itself) is what must throw.
            assertThrows(IOException.class, () -> httpGet(socket, "/ping"));
        }
    }

    @Test
    void mTls_requireAcceptsClientWithTrustedCertificate(@TempDir java.nio.file.Path dir) throws Exception {
        var serverKs = TestKeystores.build(dir, "server.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        var clientKs = TestKeystores.build(dir, "client.p12", "changeit", TestKeystores.Entry.of("cli", "test-client"));

        // Escape hatch: mutual trust needs a trust store on both sides, which the keystore()
        // convenience path deliberately doesn't expose (see TlsConfig javadoc) — this is
        // exactly the case ofContext() exists for.
        KeyStore serverIdentity = load(serverKs, "changeit");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverIdentity, "changeit".toCharArray());

        KeyStore clientTrust = load(clientKs, "changeit"); // client's own cert acts as its trust anchor here
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(clientTrust);

        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .tls(TlsConfig.ofContext(serverCtx).clientAuth(ClientAuth.REQUIRE))
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        KeyStore clientIdentity = load(clientKs, "changeit");
        KeyManagerFactory clientKmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        clientKmf.init(clientIdentity, "changeit".toCharArray());
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(clientKmf.getKeyManagers(), new javax.net.ssl.TrustManager[] { trustAll() }, null);

        try (SSLSocket socket = (SSLSocket) clientCtx.getSocketFactory().createSocket("127.0.0.1", port)) {
            String response = httpGet(socket, "/ping");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        }
    }

    private static KeyStore load(java.nio.file.Path path, String password) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = java.nio.file.Files.newInputStream(path)) { store.load(in, password.toCharArray()); }
        return store;
    }

    private static javax.net.ssl.X509TrustManager trustAll() {
        return new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    // ── Multiple listeners, one app ──────────────────────────────────────────

    @Test
    void multipleListeners_plainAndTlsServeTheSameApp(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "id.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        int plainPort = freePort();
        int tlsPort   = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .listener(new FlashConfiguration.Listener(plainPort, "127.0.0.1", null))
                .listener(new FlashConfiguration.Listener(tlsPort, "127.0.0.1", TlsConfig.keystore(ks, "changeit")))
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        try (Socket socket = new Socket("127.0.0.1", plainPort)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        }

        SSLSocketFactory factory = TestKeystores.trustAllClientContext().getSocketFactory();
        try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", tlsPort)) {
            String response = httpGet(socket, "/ping");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
        }
    }

    // ── KeyManager failure isolation ──────────────────────────────────────────

    /** Throws on its first invocation (any keyType), then delegates normally — simulates a
     *  one-off failure in a custom KeyManager (e.g. a failed DB lookup or on-demand cert
     *  issuance) without permanently breaking the listener. */
    private static final class FlakyKeyManager extends X509ExtendedKeyManager {
        private final X509ExtendedKeyManager delegate;
        private final AtomicInteger calls = new AtomicInteger();

        FlakyKeyManager(X509ExtendedKeyManager delegate) { this.delegate = delegate; }

        private void maybeFail() {
            if (calls.getAndIncrement() == 0) throw new RuntimeException("simulated KeyManager failure");
        }

        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, javax.net.ssl.SSLEngine engine) {
            maybeFail();
            return delegate.chooseEngineServerAlias(keyType, issuers, engine);
        }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            maybeFail();
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return delegate.getClientAliases(keyType, issuers); }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return delegate.chooseClientAlias(keyType, issuers, socket); }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return delegate.getServerAliases(keyType, issuers); }
        @Override public X509Certificate[] getCertificateChain(String alias) { return delegate.getCertificateChain(alias); }
        @Override public PrivateKey getPrivateKey(String alias) { return delegate.getPrivateKey(alias); }
    }

    @Test
    void keyManagerFailure_isolatedToOneConnection_listenerServesTheNext(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "id.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        KeyStore store = load(ks, "changeit");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());

        KeyManager[] managers = kmf.getKeyManagers();
        for (int i = 0; i < managers.length; i++) {
            if (managers[i] instanceof X509ExtendedKeyManager x509) managers[i] = new FlakyKeyManager(x509);
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(managers, null, null);

        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .tls(TlsConfig.ofContext(ctx))
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        SSLSocketFactory factory = TestKeystores.trustAllClientContext().getSocketFactory();

        // Connection 1: the KeyManager throws — handshake must fail, but must not take the
        // listener down with it.
        try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            assertThrows(IOException.class, socket::startHandshake);
        }

        // Connection 2: same listener, no reconfiguration — must succeed and serve normally.
        try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", port)) {
            String response = httpGet(socket, "/ping");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.endsWith("pong"));
        }
    }
}
