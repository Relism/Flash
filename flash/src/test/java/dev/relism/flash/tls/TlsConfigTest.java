package dev.relism.flash.tls;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocket;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TlsConfigTest {

    private static SSLServerSocket unboundSocket(TlsConfig tls) throws IOException {
        return (SSLServerSocket) tls.serverSocketFactory().createServerSocket();
    }

    @Test
    void keystore_pinsProtocolsToTls12And13(@TempDir Path dir) throws Exception {
        Path ks = TestKeystores.build(dir, "id.p12", "changeit",
                TestKeystores.Entry.of("only", "single.test"));
        TlsConfig tls = TlsConfig.keystore(ks, "changeit");

        try (SSLServerSocket socket = unboundSocket(tls)) {
            tls.applyTo(socket);
            List<String> protocols = Arrays.asList(socket.getSSLParameters().getProtocols());
            assertTrue(protocols.contains("TLSv1.2"));
            assertTrue(protocols.contains("TLSv1.3"));
            assertFalse(protocols.contains("SSLv3"));
            assertFalse(protocols.contains("TLSv1"));
            assertFalse(protocols.contains("TLSv1.1"));
        }
    }

    @Test
    void ofContext_appliesNoParameterOverlay() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext(); // any valid SSLContext will do here
        TlsConfig tls = TlsConfig.ofContext(ctx);

        try (SSLServerSocket socket = unboundSocket(tls)) {
            SSLParameters before = socket.getSSLParameters();
            String[] protocolsBefore = before.getProtocols();

            tls.applyTo(socket);

            assertArrayEquals(protocolsBefore, socket.getSSLParameters().getProtocols(),
                    "ofContext must not narrow/override protocols set on the caller's SSLContext");
            assertFalse(socket.getNeedClientAuth());
            assertFalse(socket.getWantClientAuth());
        }
    }

    @Test
    void ofContext_preservesCallerConfiguredAlpnAndProtocols() throws Exception {
        // Mirrors the real use case this escape hatch exists for (TLS-ALPN-01 / RFC 8737):
        // the caller sets its own ALPN protocol list — and, to make the point unambiguous,
        // a protocol list *narrower* than what Flash's own keystore() path would pin — directly
        // on the socket. applyTo() must not touch either. There is no SSLContext#setDefault-
        // SSLParameters in the public JSSE API, so this per-socket SSLParameters is the only
        // place such configuration can live; this test is the contract that makes it safe to
        // rely on.
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx).clientAuth(ClientAuth.OPTIONAL);

        try (SSLServerSocket socket = unboundSocket(tls)) {
            SSLParameters custom = socket.getSSLParameters();
            custom.setApplicationProtocols(new String[] { "acme-tls/1", "http/1.1" });
            custom.setProtocols(new String[] { "TLSv1.3" });
            socket.setSSLParameters(custom);

            tls.applyTo(socket);

            SSLParameters after = socket.getSSLParameters();
            assertArrayEquals(new String[] { "acme-tls/1", "http/1.1" }, after.getApplicationProtocols(),
                    "ofContext must not touch ALPN protocols the caller configured on its own socket");
            assertArrayEquals(new String[] { "TLSv1.3" }, after.getProtocols(),
                    "ofContext must not widen/override the caller's own protocol list");
            // clientAuth still applies — it is the caller's own explicit instruction through
            // this API, not a Flash-imposed default. See TlsConfig's class Javadoc.
            assertTrue(socket.getWantClientAuth());
        }
    }

    @Test
    void clientAuth_none_makesNoClientAuthCall() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx);

        try (SSLServerSocket socket = unboundSocket(tls)) {
            tls.applyTo(socket);
            assertFalse(socket.getNeedClientAuth());
            assertFalse(socket.getWantClientAuth());
        }
    }

    @Test
    void clientAuth_require_setsNeedClientAuth() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx).clientAuth(ClientAuth.REQUIRE);

        try (SSLServerSocket socket = unboundSocket(tls)) {
            tls.applyTo(socket);
            assertTrue(socket.getNeedClientAuth());
        }
    }

    @Test
    void clientAuth_optional_setsWantClientAuth() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx).clientAuth(ClientAuth.OPTIONAL);

        try (SSLServerSocket socket = unboundSocket(tls)) {
            tls.applyTo(socket);
            assertTrue(socket.getWantClientAuth());
            assertFalse(socket.getNeedClientAuth());
        }
    }

    // --- EX-31: cipher suite filtering when h2 is offered -----------------------

    @Test
    void negotiatesH2_trueOnlyWhenH2IsInTheOfferedList() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        assertTrue(TlsConfig.ofContext(ctx).applicationProtocols("h2", "http/1.1").negotiatesH2());
        assertTrue(TlsConfig.ofContext(ctx).applicationProtocols("h2").negotiatesH2());
        assertFalse(TlsConfig.ofContext(ctx).applicationProtocols("http/1.1").negotiatesH2());
        assertFalse(TlsConfig.ofContext(ctx).negotiatesH2()); // no applicationProtocols call at all
    }

    @Test
    void applyTo_withH2Offered_removesBlockedTls12CipherSuites() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx).applicationProtocols("h2", "http/1.1");

        try (SSLServerSocket socket = unboundSocket(tls)) {
            tls.applyTo(socket);
            List<String> enabled = Arrays.asList(socket.getEnabledCipherSuites());
            // Spot-check a handful of RFC 9113 Appendix A entries across different families
            // (RSA key exchange, 3DES, plain ECDHE-CBC) rather than the full ~280-entry list —
            // TLS12_H2_BLOCKED_CIPHERS itself is the source of truth for the complete set.
            assertFalse(enabled.contains("TLS_RSA_WITH_AES_128_CBC_SHA"));
            assertFalse(enabled.contains("TLS_RSA_WITH_3DES_EDE_CBC_SHA"));
            assertFalse(enabled.contains("TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA"));
            assertFalse(enabled.contains("TLS_NULL_WITH_NULL_NULL"));
        }
    }

    @Test
    void applyTo_withH2Offered_keepsTheRequiredCipherSuiteWhenTheJdkEnabledIt() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx).applicationProtocols("h2");

        try (SSLServerSocket socket = unboundSocket(tls)) {
            boolean jdkEnabledItByDefault =
                    Arrays.asList(socket.getEnabledCipherSuites()).contains(TlsConfig.REQUIRED_H2_CIPHER_SUITE);
            tls.applyTo(socket);
            if (jdkEnabledItByDefault) {
                assertTrue(Arrays.asList(socket.getEnabledCipherSuites()).contains(TlsConfig.REQUIRED_H2_CIPHER_SUITE),
                        "RFC 9113 §9.2.2 requires supporting this suite — filtering must never remove it");
            }
        }
    }

    @Test
    void applyTo_withoutH2Offered_leavesCipherSuitesUntouched() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx).applicationProtocols("http/1.1");

        try (SSLServerSocket socket = unboundSocket(tls)) {
            List<String> before = Arrays.asList(socket.getEnabledCipherSuites());
            tls.applyTo(socket);
            assertEquals(before, Arrays.asList(socket.getEnabledCipherSuites()));
        }
    }

    @Test
    void applyTo_noApplicationProtocolsAtAll_leavesCipherSuitesUntouched() throws Exception {
        SSLContext ctx = TestKeystores.trustAllClientContext();
        TlsConfig tls = TlsConfig.ofContext(ctx);

        try (SSLServerSocket socket = unboundSocket(tls)) {
            List<String> before = Arrays.asList(socket.getEnabledCipherSuites());
            tls.applyTo(socket);
            assertEquals(before, Arrays.asList(socket.getEnabledCipherSuites()));
        }
    }
}
