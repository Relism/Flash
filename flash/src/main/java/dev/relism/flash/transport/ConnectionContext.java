package dev.relism.flash.transport;

import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.AbstractWsRouter;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLSocket;

/**
 * Everything a {@link ConnectionProtocol} implementation needs to serve one connection, bundled
 * into a single object instead of a long parameter list.
 *
 * @param socket the accepted socket — owns its lifecycle (closing it is the caller's, i.e. {@link
 *     ConnectionRunner}'s, responsibility, not the protocol's)
 * @param sslSocket {@code socket} narrowed to {@link SSLSocket}, or {@code null} for a plaintext
 *     connection
 * @param in the single buffered, deadline-aware source for this connection's inbound bytes
 * @param out the buffered output stream — for header/body writes that benefit from userspace
 *     coalescing before a single syscall
 * @param rawOut the unbuffered output stream — for WebSocket, whose writes are already bulk (see
 *     {@code WebSocketSession})
 * @param remoteAddress the client's address, or {@code null} if unavailable
 * @param router the HTTP router
 * @param wsRouter the WebSocket router
 * @param configuration the server configuration (timeouts, limits, feature flags)
 * @param stopped {@code true} once the server has begun shutting down — a protocol implementation's
 *     request loop must check this and exit promptly
 */
public record ConnectionContext(
    Socket socket,
    SSLSocket sslSocket,
    BufferedByteSource in,
    OutputStream out,
    OutputStream rawOut,
    InetSocketAddress remoteAddress,
    ConnectionScratch scratch,
    AbstractRouter router,
    AbstractWsRouter wsRouter,
    FlashConfiguration configuration,
    ExecutorService executor,
    BooleanSupplier stopped) {}
