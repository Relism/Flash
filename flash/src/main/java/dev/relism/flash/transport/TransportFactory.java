package dev.relism.flash.transport;

import dev.relism.flash.ServerHandle;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.http1.Http1Connection;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.AbstractWsRouter;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Composes the whole transport: binds every configured listener, wires the connection runner
 * and the h1 protocol, and returns the {@link ServerHandle} implementation
 * ({@link ServerLifecycle}) that {@link dev.relism.flash.ServerHandle#create} exposes publicly.
 *
 * <p>{@code EX-34}: this is the "composed transport rather than a god object" the registry
 * asked for — {@code ServerHandle.create} used to construct {@code HttpServer} directly, which
 * no longer exists. Package-private-in-spirit (public only because {@code ServerHandle} lives
 * in a different package and must call it) — user code has no reason to call this directly.
 */
@Slf4j
public final class TransportFactory {

    private TransportFactory() {
    }

    public static ServerHandle create(FlashConfiguration configuration,
                                       AbstractRouter router, AbstractWsRouter wsRouter) throws IOException {
        List<FlashConfiguration.Listener> specs = configuration.getListeners().isEmpty()
                ? List.of(new FlashConfiguration.Listener(
                        configuration.getPort(), configuration.getHost(), configuration.getTls()))
                : configuration.getListeners();

        List<BoundListener> bound = new ArrayList<>(specs.size());
        for (FlashConfiguration.Listener spec : specs) bound.add(ListenerBinder.bind(spec));
        List<BoundListener> boundListeners = List.copyOf(bound);

        for (BoundListener bl : boundListeners) {
            log.info("HTTP server bound on {}:{} (tls={}, backlog={}, acceptThreads={})",
                    bl.socket().getInetAddress(), bl.socket().getLocalPort(), bl.secure(),
                    TransportTuning.ACCEPT_BACKLOG, TransportTuning.ACCEPT_THREADS);
        }

        ExecutorService executorService = Executors.newVirtualThreadPerTaskExecutor();
        Set<Socket>     activeSockets   = ConcurrentHashMap.newKeySet();
        ScratchPool     scratchPool     = new ScratchPool();

        ConnectionRunner runner = new ConnectionRunner(
                executorService, activeSockets, scratchPool, router, wsRouter, configuration,
                new Http1Connection());

        return new ServerLifecycle(boundListeners, runner, configuration, executorService, activeSockets);
    }
}
