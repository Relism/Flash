package dev.relism.flash.transport;

import javax.net.ssl.SSLSocket;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Decides, once per connection and before any request is parsed, whether the connection speaks
 * HTTP/1.1 or HTTP/2 — the single seam R1 requires ("the protocol decision is made once,
 * immediately after ALPN/preface detection").
 *
 * <p>Two independent signals, in order:
 * <ol>
 *   <li><b>ALPN</b> (TLS connections). If the socket is an {@link SSLSocket} and the TLS
 *       handshake already resolved {@code "h2"} as the application protocol, this connection is
 *       {@link NegotiatedProtocol#H2}. Anything else negotiated — {@code "http/1.1"}, no
 *       protocol at all (a peer that doesn't speak ALPN), or an empty string — is
 *       {@link NegotiatedProtocol#HTTP_1_1}. This costs nothing beyond a field read: ALPN is
 *       resolved during the handshake, which must already have completed (see
 *       {@code TlsConfig}'s Javadoc on why {@code startHandshake()} must be called explicitly
 *       before this method runs — {@code EX-30}).</li>
 *   <li><b>h2c prior knowledge</b> (plaintext connections, RFC 9113 §3.4). The first 24 bytes of
 *       the connection are compared, without being consumed, against the client connection
 *       preface {@code "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n"}. A match is
 *       {@link NegotiatedProtocol#H2}; anything else — including a partial match followed by
 *       EOF, or a preface look-alike that diverges partway through — is
 *       {@link NegotiatedProtocol#HTTP_1_1}. This is why {@link BufferedByteSource#peek} exists:
 *       the bytes must remain available for {@code RequestParser} if they turn out not to be an
 *       h2 preface after all.</li>
 * </ol>
 *
 * <p>This method reports the protocol accurately and unconditionally — it does not consult
 * {@code FlashConfiguration.http2Enabled}. Gating whether an {@link NegotiatedProtocol#H2}
 * result is honoured (versus cleanly rejected, which is all Phase 1 can do — there is no
 * {@code Http2Connection} yet) and whether the h2c peek is even attempted for plaintext
 * connections are both the caller's responsibility, so that this class stays a pure,
 * directly-testable detector (see {@code ProtocolNegotiatorTest}).
 */
public final class ProtocolNegotiator {

    /**
     * The HTTP/2 client connection preface (RFC 9113 §3.4) — precompiled once (R4), never
     * reconstructed per connection.
     */
    private static final byte[] H2C_PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    private ProtocolNegotiator() {
    }

    public static NegotiatedProtocol negotiate(Socket socket, BufferedByteSource source) throws IOException {
        if (socket instanceof SSLSocket ssl) {
            String applicationProtocol = ssl.getApplicationProtocol();
            return "h2".equals(applicationProtocol) ? NegotiatedProtocol.H2 : NegotiatedProtocol.HTTP_1_1;
        }

        byte[] probe = new byte[H2C_PREFACE.length];
        int n = source.peek(probe, 0, probe.length);
        if (n == H2C_PREFACE.length && Arrays.equals(probe, H2C_PREFACE)) {
            return NegotiatedProtocol.H2;
        }
        return NegotiatedProtocol.HTTP_1_1;
    }
}
