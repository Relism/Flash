package dev.relism.flash.transport;

import dev.relism.flash.ServerConfig;
import dev.relism.flash.tls.TlsConfig;

import javax.net.ssl.SSLServerSocket;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;

/**
 * Turns a {@link ServerConfig.Listener} into a bound {@link ServerSocket}. Sole
 * responsibility: binding — not accepting, not connection handling.
 *
 * <p>A TLS listener gets its {@link ServerSocket} from {@link TlsConfig#serverSocketFactory()}
 * instead of {@code new ServerSocket()}, and its protocol/client-auth/cipher parameters from
 * {@link TlsConfig#applyTo}; reuse-address, receive buffer size, backlog and the bind call
 * itself are identical either way. TLS only changes which bytes come out of {@code accept()};
 * it never changes how the accept loop, or anything downstream of it, treats them.
 */
public final class ListenerBinder {

    private ListenerBinder() {
    }

    public static BoundListener bind(ServerConfig.Listener spec) throws IOException {
        TlsConfig tls = spec.tls();

        ServerSocket socket = tls != null ? tls.serverSocketFactory().createServerSocket() : new ServerSocket();
        // setReuseAddress(true) must be called BEFORE bind().
        socket.setReuseAddress(true);
        socket.setReceiveBufferSize(TransportTuning.SOCKET_BUF_SIZE);
        if (tls != null) tls.applyTo((SSLServerSocket) socket);

        InetSocketAddress addr = spec.host() != null
                ? new InetSocketAddress(spec.host(), spec.port())
                : new InetSocketAddress(spec.port());
        socket.bind(addr, TransportTuning.ACCEPT_BACKLOG);

        return new BoundListener(socket, tls != null);
    }
}
