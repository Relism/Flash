package dev.relism.flash.transport;

import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.AbstractWsRouter;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.net.ssl.SSLSocket;
import lombok.extern.slf4j.Slf4j;

/**
 * Owns one connection's socket lifecycle from accept to close: configures socket options,
 * dispatches to the matching {@link ConnectionProtocol} — guaranteeing cleanup (scratch release,
 * active-socket tracking) regardless of how the protocol implementation exits.
 *
 * <p>Sole responsibility: connection setup/teardown. It contains no HTTP semantics at all — those
 * live entirely inside whichever {@link ConnectionProtocol} it dispatches to (today, always {@code
 * Http1Connection}; an {@code H2} negotiation result is closed cleanly, since
 */
@Slf4j
public final class ConnectionRunner {

  private final ExecutorService executorService;
  private final Set<Socket> activeSockets;
  private final ScratchPool scratchPool;
  private final AbstractRouter router;
  private final AbstractWsRouter wsRouter;
  private final FlashConfiguration configuration;
  private final ConnectionProtocol http1Protocol;
  private final Supplier<? extends ConnectionProtocol> http2ProtocolFactory;

  public ConnectionRunner(
      ExecutorService executorService,
      Set<Socket> activeSockets,
      ScratchPool scratchPool,
      AbstractRouter router,
      AbstractWsRouter wsRouter,
      FlashConfiguration configuration,
      ConnectionProtocol http1Protocol,
      Supplier<? extends ConnectionProtocol> http2ProtocolFactory) {
    this.executorService = executorService;
    this.activeSockets = activeSockets;
    this.scratchPool = scratchPool;
    this.router = router;
    this.wsRouter = wsRouter;
    this.configuration = configuration;
    this.http1Protocol = http1Protocol;
    this.http2ProtocolFactory = http2ProtocolFactory;
  }

  /**
   * Submits {@code socket} to the virtual-thread executor for full connection handling. {@code
   * stopped} is threaded through to the eventual {@link ConnectionContext} so the protocol
   * implementation can observe an in-progress graceful shutdown.
   */
  public void accept(Socket socket, BooleanSupplier stopped) {
    try {
      executorService.submit(() -> handle(socket, stopped));
    } catch (RejectedExecutionException ignored) {
      try {
        socket.close();
      } catch (IOException e) {
        log.debug("Error closing socket on shutdown", e);
      }
    }
  }

  private void handle(Socket socket, BooleanSupplier stopped) {
    activeSockets.add(socket);
    ConnectionScratch scratch = scratchPool.acquire();
    try (socket;
        OutputStream out = new BufferedOutputStream(socket.getOutputStream())) {

      // TCP_NODELAY: disable Nagle's algorithm. Small WS frames (< MSS) are sent
      // immediately rather than waiting up to 200 ms for more data to coalesce.
      socket.setTcpNoDelay(true);
      socket.setSendBufferSize(TransportTuning.SOCKET_BUF_SIZE);

      SSLSocket sslSocket = socket instanceof SSLSocket ssl ? ssl : null;
      if (sslSocket != null) {
        // protocol decision — SSLSocket#getApplicationProtocol() (which
        // ProtocolNegotiator relies on) returns null until the handshake has run.
        socket.setSoTimeout(configuration.getHeaderReadTimeoutMs());
        sslSocket.startHandshake();
        socket.setSoTimeout(0); // BufferedByteSource's own deadline takes over below
      }

      // rawOut is the unbuffered socket stream — passed to WebSocketSession directly.
      // WS writes are already bulk; HTTP responses use the buffered `out` because
      // Http1ResponseWriter does several small writes that benefit from coalescing.
      OutputStream rawOut = socket.getOutputStream();
      BufferedByteSource in = new BufferedByteSource(socket.getInputStream(), socket);

      NegotiatedProtocol negotiated = negotiateProtocol(socket, in);
      ConnectionContext ctx =
          new ConnectionContext(
              socket,
              sslSocket,
              in,
              out,
              rawOut,
              (InetSocketAddress) socket.getRemoteSocketAddress(),
              scratch,
              router,
              wsRouter,
              configuration,
              executorService,
              stopped);
      if (negotiated == NegotiatedProtocol.HTTP_2) http2ProtocolFactory.get().run(ctx);
      else http1Protocol.run(ctx);

    } catch (IOException e) {
      if (!stopped.getAsBoolean()) {
        if (e instanceof SocketException) log.debug("Connection closed: {}", e.getMessage());
        else log.error("I/O error handling request", e);
      }
    } catch (Exception e) {
      // Anything not an IOException here means a collaborator misbehaved on the TLS
      // handshake path — most likely a custom TlsConfig#ofContext KeyManager/TrustManager
      // throwing. That failure is isolated to this one virtual thread/connection.
      if (!stopped.getAsBoolean()) log.error("Unexpected error handling connection", e);
    } finally {
      activeSockets.remove(socket);
      scratchPool.release(scratch);
    }
  }

  /**
   * Decides h1 vs h2 for this connection, applying {@link FlashConfiguration#isHttp2Enabled()} to
   * the plaintext (h2c) path — see {@link ProtocolNegotiator}'s Javadoc for why the flag is applied
   * here rather than inside the negotiator itself.
   */
  private NegotiatedProtocol negotiateProtocol(Socket socket, BufferedByteSource in)
      throws IOException {
    if (socket instanceof SSLSocket) {
      return ProtocolNegotiator.negotiate(socket, in); // ALPN — already resolved, no I/O
    }
    if (!configuration.isHttp2Enabled()) {
      return NegotiatedProtocol.HTTP_1_1; // skip the h2c peek entirely when disabled
    }
    in.setDeadline(System.nanoTime() + configuration.getIdleKeepAliveTimeoutMs() * 1_000_000L);
    try {
      return ProtocolNegotiator.negotiate(socket, in);
    } finally {
      in.clearDeadline();
    }
  }
}
