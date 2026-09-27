package dev.relism.flash.transport;

import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link ProtocolNegotiator#negotiate} is a pure, directly-testable detector (see its Javadoc
 * for why it does not itself consult {@code ServerConfig}) — every case here
 * calls it directly rather than through {@code Http1Connection}/{@code ConnectionRunner}.
 */
class ProtocolNegotiatorTest {

    // ── Plaintext (h2c prior knowledge) — no real networking needed ────────────

    private static BufferedByteSource plaintextSource(String bytes) {
        return new BufferedByteSource(
                new ByteArrayInputStream(bytes.getBytes(StandardCharsets.US_ASCII)), new Socket());
    }

    @Test
    void h2cPrefaceExact_negotiatesH2() throws IOException {
        BufferedByteSource src = plaintextSource("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n");
        assertEquals(NegotiatedProtocol.HTTP_2, ProtocolNegotiator.negotiate(new Socket(), src));
    }

    @Test
    void h2cPrefaceFollowedByMoreData_stillNegotiatesH2_andDoesNotConsume() throws IOException {
        BufferedByteSource src = plaintextSource("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\nEXTRA");
        assertEquals(NegotiatedProtocol.HTTP_2, ProtocolNegotiator.negotiate(new Socket(), src));
        // peek() must not have consumed anything — the full 24-byte preface is still there for
        // whatever reads next (Http2Connection, once it exists).
        byte[] readBack = new byte[24];
        assertEquals(24, src.read(readBack, 0, 24));
        assertEquals("PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n",
                new String(readBack, StandardCharsets.US_ASCII));
    }

    @Test
    void partialPreface_thenEof_negotiatesHttp1_notConsumed() throws IOException {
        // Fewer than 24 bytes total, then EOF — not a match, and RequestParser must still see
        // every byte that was actually sent.
        BufferedByteSource src = plaintextSource("PRI * HTTP/2.0\r\n");
        assertEquals(NegotiatedProtocol.HTTP_1_1, ProtocolNegotiator.negotiate(new Socket(), src));
        byte[] readBack = new byte[16];
        assertEquals(16, src.read(readBack, 0, 16));
        assertEquals("PRI * HTTP/2.0\r\n", new String(readBack, StandardCharsets.US_ASCII));
    }

    @Test
    void prefaceLookalike_divergesPartway_negotiatesHttp1() throws IOException {
        // "PRI " matches, then diverges — must not be misdetected as h2c.
        BufferedByteSource src = plaintextSource("PRI * HTTP/9.9\r\n\r\nXX\r\n\r\n");
        assertEquals(NegotiatedProtocol.HTTP_1_1, ProtocolNegotiator.negotiate(new Socket(), src));
    }

    @Test
    void plainGetRequest_negotiatesHttp1() throws IOException {
        BufferedByteSource src = plaintextSource("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n");
        assertEquals(NegotiatedProtocol.HTTP_1_1, ProtocolNegotiator.negotiate(new Socket(), src));
        byte[] readBack = new byte[15];
        assertEquals(15, src.read(readBack, 0, 15));
        assertEquals("GET / HTTP/1.1\r", new String(readBack, StandardCharsets.US_ASCII));
    }

    // ── TLS/ALPN — a real loopback handshake, since ALPN is resolved during it ─

    private interface ThrowingConsumer<T> { void accept(T t) throws Exception; }

    /**
     * Binds a real TLS listener offering {@code serverAlpn}, connects a client offering
     * {@code clientAlpn}, forces the handshake on both sides (mirroring {@code Http1Connection}/{@code ConnectionRunner}'s
     */
    private static void withNegotiatedAlpn(Path dir, String[] serverAlpn, String[] clientAlpn,
                                            ThrowingConsumer<SSLSocket> assertion) throws Exception {
        Path ks = TestKeystores.build(dir, "negotiator.p12", "changeit",
                TestKeystores.Entry.of("only", "negotiator.test"));
        TlsConfig serverTls = TlsConfig.keystore(ks, "changeit");
        if (serverAlpn != null) serverTls = serverTls.applicationProtocols(serverAlpn);

        try (SSLServerSocket serverSocket = (SSLServerSocket) serverTls.serverSocketFactory().createServerSocket()) {
            serverTls.applyTo(serverSocket);
            serverSocket.bind(new InetSocketAddress("127.0.0.1", 0));
            int port = serverSocket.getLocalPort();

            CompletableFuture<SSLSocket> accepted = CompletableFuture.supplyAsync(() -> {
                try {
                    SSLSocket s = (SSLSocket) serverSocket.accept();
                    s.startHandshake();
                    return s;
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });

            SSLSocketFactory clientFactory = TestKeystores.trustAllClientContext().getSocketFactory();
            try (SSLSocket client = (SSLSocket) clientFactory.createSocket("127.0.0.1", port)) {
                if (clientAlpn != null) {
                    javax.net.ssl.SSLParameters params = client.getSSLParameters();
                    params.setApplicationProtocols(clientAlpn);
                    client.setSSLParameters(params);
                }
                client.startHandshake();

                try (SSLSocket server = accepted.get()) {
                    assertion.accept(server);
                }
            }
        }
    }

    @Test
    void alpnH2_negotiatesH2(@TempDir Path dir) throws Exception {
        withNegotiatedAlpn(dir, new String[]{"h2", "http/1.1"}, new String[]{"h2", "http/1.1"}, server -> {
            assertEquals("h2", server.getApplicationProtocol());
            assertEquals(NegotiatedProtocol.HTTP_2,
                    ProtocolNegotiator.negotiate(server, new BufferedByteSource(server.getInputStream(), server)));
        });
    }

    @Test
    void alpnHttp11_negotiatesHttp1(@TempDir Path dir) throws Exception {
        withNegotiatedAlpn(dir, new String[]{"h2", "http/1.1"}, new String[]{"http/1.1"}, server -> {
            assertEquals("http/1.1", server.getApplicationProtocol());
            assertEquals(NegotiatedProtocol.HTTP_1_1,
                    ProtocolNegotiator.negotiate(server, new BufferedByteSource(server.getInputStream(), server)));
        });
    }

    @Test
    void alpnAbsent_negotiatesHttp1(@TempDir Path dir) throws Exception {
        // Neither side offers ALPN at all — the common case today.
        withNegotiatedAlpn(dir, null, null, server -> {
            assertEquals(NegotiatedProtocol.HTTP_1_1,
                    ProtocolNegotiator.negotiate(server, new BufferedByteSource(server.getInputStream(), server)));
        });
    }
}
