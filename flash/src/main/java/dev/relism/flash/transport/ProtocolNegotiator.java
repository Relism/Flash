package dev.relism.flash.transport;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import javax.net.ssl.SSLSocket;

/** Detects HTTP/1.1 or HTTP/2 once, before the connection parser is selected. */
public final class ProtocolNegotiator {
  private static final byte[] H2C_PREFACE =
      "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private ProtocolNegotiator() {}

  /**
   * Uses the completed TLS ALPN result for secure sockets and a non-consuming prior-knowledge
   * preface probe for plaintext sockets. Configuration gates remain the caller's responsibility,
   * which keeps detection deterministic and independently testable.
   */
  public static NegotiatedProtocol negotiate(Socket socket, BufferedByteSource source)
      throws IOException {
    if (socket instanceof SSLSocket ssl) {
      return "h2".equals(ssl.getApplicationProtocol())
          ? NegotiatedProtocol.HTTP_2
          : NegotiatedProtocol.HTTP_1_1;
    }

    byte[] probe = new byte[H2C_PREFACE.length];
    int read = source.peek(probe, 0, probe.length);
    return read == H2C_PREFACE.length && Arrays.equals(probe, H2C_PREFACE)
        ? NegotiatedProtocol.HTTP_2
        : NegotiatedProtocol.HTTP_1_1;
  }
}
