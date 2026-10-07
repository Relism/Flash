package dev.relism.flash;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.tls.ClientAuth;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
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
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end TLS coverage over real sockets — same style as {@link HttpServerTest}, just with
 * an {@link SSLSocketFactory} client instead of a raw one. Certificates are generated per test
 * via {@link TestKeystores} (JDK {@code keytool}, no fixture files, no extra crypto dependency).
 */
class HttpServerTlsTest {

    private static final int SOCKET_TIMEOUT_MS = 5000;

    private App app;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
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
        ServerConfig config1 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config1);
        int port = server.port();

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
        ServerConfig config2 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config2);
        int port = server.port();

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
        ServerConfig config3 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit").clientAuth(ClientAuth.REQUIRE))
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config3);
        int port = server.port();

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

        ServerConfig config4 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.ofContext(serverCtx).clientAuth(ClientAuth.REQUIRE))
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config4);
        int port = server.port();

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
        ServerConfig config5 = ServerConfig.builder()
                .listener(new ServerConfig.Listener(0, "127.0.0.1", null))
                .listener(new ServerConfig.Listener(0, "127.0.0.1", TlsConfig.keystore(ks, "changeit")))
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config5);
        int plainPort = server.ports().get(0);
        int tlsPort   = server.ports().get(1);

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

        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
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

        ServerConfig config6 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.ofContext(ctx))
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config6);
        int port = server.port();

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

    // ── ALPN visibility for on-demand cert issuance (TLS-ALPN-01 / RFC 8737) ──

    /** Delegates alias selection, but first records what the KeyManager itself observed as the
     *  negotiated ALPN protocol at the exact point a real ACME-style KeyManager would decide
     *  whether to serve a challenge certificate instead of the real one. */
    private static final class RecordingKeyManager extends X509ExtendedKeyManager {
        private final X509ExtendedKeyManager delegate;
        private final AtomicReference<String> observedProtocol = new AtomicReference<>();

        RecordingKeyManager(X509ExtendedKeyManager delegate) { this.delegate = delegate; }

        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            observedProtocol.set(engine.getHandshakeApplicationProtocol());
            return delegate.chooseEngineServerAlias(keyType, issuers, engine);
        }
        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            observedProtocol.set(((SSLSocket) socket).getHandshakeApplicationProtocol());
            return delegate.chooseServerAlias(keyType, issuers, socket);
        }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return delegate.getClientAliases(keyType, issuers); }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return delegate.chooseClientAlias(keyType, issuers, socket); }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return delegate.getServerAliases(keyType, issuers); }
        @Override public X509Certificate[] getCertificateChain(String alias) { return delegate.getCertificateChain(alias); }
        @Override public PrivateKey getPrivateKey(String alias) { return delegate.getPrivateKey(alias); }
    }

    private static RecordingKeyManager buildRecordingContext(java.nio.file.Path ks, SSLContext[] outCtx) throws Exception {
        KeyStore store = load(ks, "changeit");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());

        RecordingKeyManager recorder = null;
        KeyManager[] managers = kmf.getKeyManagers();
        for (int i = 0; i < managers.length; i++) {
            if (managers[i] instanceof X509ExtendedKeyManager x509) {
                recorder = new RecordingKeyManager(x509);
                managers[i] = recorder;
            }
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(managers, null, null);
        outCtx[0] = ctx;
        return recorder;
    }

    @Test
    void alpn_negotiatedProtocolIsVisibleToKeyManagerBeforeCertificateIsChosen(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "id.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));

        SSLContext[] boxedCtx = new SSLContext[1];
        RecordingKeyManager recorder = buildRecordingContext(ks, boxedCtx);

        ServerConfig config7 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.ofContext(boxedCtx[0]).applicationProtocols("acme-tls/1", "http/1.1"))
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config7);
        int port = server.port();

        // Each connection below uses its own fresh client SSLContext, deliberately — reusing one
        // SSLContext across connections lets JSSE resume the second handshake's TLS session,
        // which skips the Certificate message (and therefore the KeyManager call) entirely. A
        // fresh SSLContext has nothing to resume, forcing a full handshake every time, which is
        // what this test needs to observe the KeyManager on every connection.

        // A client offering only the ACME challenge protocol: the KeyManager must see it
        // *before* choosing which certificate to serve — this is the hook the challenge-cert
        // decision hangs off.
        try (SSLSocket socket = (SSLSocket) TestKeystores.trustAllClientContext()
                .getSocketFactory().createSocket("127.0.0.1", port)) {
            SSLParameters params = socket.getSSLParameters();
            params.setApplicationProtocols(new String[] { "acme-tls/1" });
            socket.setSSLParameters(params);
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.startHandshake();
            assertEquals("acme-tls/1", recorder.observedProtocol.get());
            assertEquals("acme-tls/1", socket.getApplicationProtocol());
        }

        // Zero regression: a normal client (http/1.1) behaves exactly as before ALPN existed —
        // same route, same response, and the KeyManager sees the normal protocol, not the
        // challenge one.
        try (SSLSocket socket = (SSLSocket) TestKeystores.trustAllClientContext()
                .getSocketFactory().createSocket("127.0.0.1", port)) {
            SSLParameters params = socket.getSSLParameters();
            params.setApplicationProtocols(new String[] { "http/1.1" });
            socket.setSSLParameters(params);
            String response = httpGet(socket, "/ping");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.endsWith("pong"));
            assertEquals("http/1.1", recorder.observedProtocol.get());
        }

        // Zero regression: a client sending no ALPN at all — today's default — is unaffected.
        try (SSLSocket socket = (SSLSocket) TestKeystores.trustAllClientContext()
                .getSocketFactory().createSocket("127.0.0.1", port)) {
            String response = httpGet(socket, "/ping");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.endsWith("pong"));
            // "" — not null — is JSSE's sentinel for "peer sent no ALPN extension at all".
            assertEquals("", recorder.observedProtocol.get());
        }
    }

    // ── Request.isSecure() / sslSession() ─────────────────────────────────────

    @Test
    void request_isSecureAndSessionAvailableOverTls(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "id.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        ServerConfig config8 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .build();
        app = App.create();
        app.get("/secure-info", (req, res) -> {
            SSLSession session = req.sslSession();
            return req.isSecure() + ":" + (session != null) + ":" + (session != null ? session.getCipherSuite() : "");
        });
        server = Server.start(app, config8);
        int port = server.port();

        try (SSLSocket socket = (SSLSocket) TestKeystores.trustAllClientContext()
                .getSocketFactory().createSocket("127.0.0.1", port)) {
            String response = httpGet(socket, "/secure-info");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.contains("true:true:TLS_"),
                    "expected isSecure=true, non-null session, real cipher suite; got: " + response);
        }
    }

    @Test
    void request_peerCertificateVisibleWhenClientPresentsOne(@TempDir java.nio.file.Path dir) throws Exception {
        var serverKs = TestKeystores.build(dir, "server.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        var clientKs = TestKeystores.build(dir, "client.p12", "changeit", TestKeystores.Entry.of("cli", "test-client"));

        KeyStore serverIdentity = load(serverKs, "changeit");
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(serverIdentity, "changeit".toCharArray());
        KeyStore clientTrust = load(clientKs, "changeit"); // client's own cert as its trust anchor, as elsewhere in this file
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(clientTrust);
        SSLContext serverCtx = SSLContext.getInstance("TLS");
        serverCtx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);

        ServerConfig config9 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                // OPTIONAL, not REQUIRE: proves getPeerCertificates() works without also
                // re-testing the REQUIRE-rejection path already covered elsewhere in this file.
                .tls(TlsConfig.ofContext(serverCtx).clientAuth(ClientAuth.OPTIONAL))
                .build();
        app = App.create();
        app.get("/secure-info", (req, res) -> {
            try {
                X509Certificate peer = (X509Certificate) req.sslSession().getPeerCertificates()[0];
                return "peer:" + peer.getSubjectX500Principal().getName();
            } catch (SSLPeerUnverifiedException e) {
                return "no-peer-cert";
            }
        });
        server = Server.start(app, config9);
        int port = server.port();

        KeyStore clientIdentity = load(clientKs, "changeit");
        KeyManagerFactory clientKmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        clientKmf.init(clientIdentity, "changeit".toCharArray());
        SSLContext clientCtx = SSLContext.getInstance("TLS");
        clientCtx.init(clientKmf.getKeyManagers(), new TrustManager[] { trustAll() }, null);

        try (SSLSocket socket = (SSLSocket) clientCtx.getSocketFactory().createSocket("127.0.0.1", port)) {
            String response = httpGet(socket, "/secure-info");
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.contains("peer:CN=test-client"), "response was: " + response);
        }
    }

    @Test
    void request_isNotSecureAndSessionIsNullOnPlainListener() throws Exception {
        ServerConfig config10 = ServerConfig.builder().port(0).host("127.0.0.1").build();
        app = App.create();
        app.get("/secure-info", (req, res) -> req.isSecure() + ":" + (req.sslSession() == null));
        server = Server.start(app, config10);
        int port = server.port();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.getOutputStream().write(
                    "GET /secure-info HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                            .getBytes(StandardCharsets.UTF_8));
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.endsWith("false:true"), "expected isSecure=false, session=null; got: " + response);
        }
    }

    // ── Request.isSecure() / sslSession() — same info, WSS path ──────

    private static String wsHandshakeKey() {
        return Base64.getEncoder().encodeToString("flash-tls-test-key".getBytes(StandardCharsets.UTF_8));
    }

    private static String readWsHandshakeResponse(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int b, prev3 = -1, prev2 = -1, prev1 = -1;
        while ((b = in.read()) != -1) {
            out.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
            prev3 = prev2; prev2 = prev1; prev1 = b;
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void wss_sessionIsSecureAndExposesSslSession(@TempDir java.nio.file.Path dir) throws Exception {
        var ks = TestKeystores.build(dir, "id.p12", "changeit", TestKeystores.Entry.of("srv", "localhost"));
        ServerConfig config11 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .build();
        app = App.create();

        AtomicReference<Boolean> observedSecure = new AtomicReference<>();
        AtomicReference<SSLSession> observedSession = new AtomicReference<>();
        // The handler runs on the server's virtual thread, asynchronously with respect to the client
        // reading the 101 response bytes below (both happen after the same flush, in either
        // order) — the latch is what makes the assertions below wait for the handler, not the socket read.
        java.util.concurrent.CountDownLatch opened = new java.util.concurrent.CountDownLatch(1);
        app.ws("/echo", new WebSocketHandler() {
          @Override protected void onOpen(WebSocketSession session) {
            observedSecure.set(session.request().isSecure());
            observedSession.set(session.request().sslSession());
            opened.countDown();
          }

          @Override protected void onMessage(WebSocketSession session, WebSocketFrame frame) {}
        });
        server = Server.start(app, config11);
        int port = server.port();

        try (SSLSocket socket = (SSLSocket) TestKeystores.trustAllClientContext()
                .getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            socket.startHandshake();
            OutputStream out = socket.getOutputStream();
            out.write(("GET /echo HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Key: " + wsHandshakeKey() + "\r\n" +
                    "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();

            String headers = readWsHandshakeResponse(socket.getInputStream());
            assertTrue(headers.startsWith("HTTP/1.1 101 Switching Protocols"), "handshake response: " + headers);
            assertTrue(opened.await(SOCKET_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
                    "the handler did not run within timeout");
        }

        assertEquals(Boolean.TRUE, observedSecure.get());
        assertNotNull(observedSession.get());
    }
}
