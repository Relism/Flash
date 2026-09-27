package dev.relism.flash.transport;

import dev.relism.flash.ServerConfig;
import dev.relism.flash.http1.Http1Connection;
import dev.relism.flash.http2.Http2Connection;
import dev.relism.flash.routing.Router;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.extern.slf4j.Slf4j;

/**
 * Composes the transport: binds every configured listener, wires the connection runner and both
 * protocols, and returns the {@link ServerLifecycle} that {@code Server} drives.
 */
@Slf4j
public final class TransportFactory {

  private TransportFactory() {}

  public static ServerLifecycle create(
      ServerConfig configuration, Router router)
      throws IOException {
    List<ServerConfig.Listener> specs =
        configuration.getListeners().isEmpty()
            ? List.of(
                new ServerConfig.Listener(
                    configuration.getPort(), configuration.getHost(), configuration.getTls()))
            : configuration.getListeners();

    List<BoundListener> bound = new ArrayList<>(specs.size());
    for (ServerConfig.Listener original : specs) {
      ServerConfig.Listener spec = original;
      if (configuration.isHttp2Enabled() && original.tls() != null) {
        spec =
            new ServerConfig.Listener(
                original.port(), original.host(), original.tls().enableHttp2Alpn());
      }
      bound.add(ListenerBinder.bind(spec));
    }
    List<BoundListener> boundListeners = List.copyOf(bound);

    for (BoundListener bl : boundListeners) {
      log.info(
          "HTTP server bound on {}:{} (tls={}, backlog={}, acceptThreads={})",
          bl.socket().getInetAddress(),
          bl.socket().getLocalPort(),
          bl.secure(),
          TransportTuning.ACCEPT_BACKLOG,
          TransportTuning.ACCEPT_THREADS);
    }

    ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
    Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    ScratchPool scratchPool = new ScratchPool();

    ConnectionRunner runner =
        new ConnectionRunner(
            executorService,
            activeSockets,
            scratchPool,
            router,
            configuration,
            new Http1Connection(),
            Http2Connection::new);

    return new ServerLifecycle(
        boundListeners, runner, configuration, executorService, activeSockets);
  }
}
