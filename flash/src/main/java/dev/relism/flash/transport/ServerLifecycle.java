package dev.relism.flash.transport;

import dev.relism.flash.ServerConfig;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.Socket;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Owns the server's lifecycle: the accept threads (one per listener ×
 * {@code TransportTuning.ACCEPT_THREADS}), the active-socket registry, and the two-stage
 * {@code shutdownDrainTimeoutMs} (during which {@code Http1Connection} forces
 * {@code Connection: close} on the next response once it observes {@link #isStopped()}), then
 * force-close whatever remains.
 *
 */
@Slf4j
public final class ServerLifecycle {

    private final List<BoundListener>  listeners;
    private final ConnectionRunner     runner;
    private final ServerConfig   configuration;
    private final ExecutorService      executorService;
    private final Set<Socket>          activeSockets;
    private final CountDownLatch       acceptLatch;
    private volatile boolean           stopped = false;

    public ServerLifecycle(List<BoundListener> listeners, ConnectionRunner runner,
                            ServerConfig configuration, ExecutorService executorService,
                            Set<Socket> activeSockets) {
        this.listeners       = listeners;
        this.runner          = runner;
        this.configuration   = configuration;
        this.executorService = executorService;
        this.activeSockets   = activeSockets;
        this.acceptLatch     = new CountDownLatch(TransportTuning.ACCEPT_THREADS * listeners.size());
    }

    public int port() { return listeners.get(0).socket().getLocalPort(); }

    public List<Integer> ports() {
        return listeners.stream().map(bound -> bound.socket().getLocalPort()).toList();
    }

    /** Whether the server has begun shutting down. Passed down to every connection as a
     *  {@link java.util.function.BooleanSupplier} so in-flight request loops can drain
     *  promptly instead of waiting for their next keep-alive request. */
    public boolean isStopped() {
        return stopped;
    }

    public void start() {
        for (int li = 0; li < listeners.size(); li++) {
            BoundListener listener = listeners.get(li);
            for (int i = 0; i < TransportTuning.ACCEPT_THREADS; i++) {
                Thread.ofPlatform()
                        .name("flash-accept-" + li + "-" + i)
                        .daemon(false)
                        .start(() -> {
                            try {
                                AcceptLoop.run(listener, runner, this::isStopped);
                            } finally {
                                acceptLatch.countDown();
                            }
                        });
            }
        }
    }

    /** Blocks until every accept loop has ended, which is what {@link #stop()} causes. */
    public void await() {
        try { acceptLatch.await(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    public CompletableFuture<Void> stop() {
        return CompletableFuture.runAsync(() -> {
            stopped = true;
            for (BoundListener bl : listeners) {
                try { bl.socket().close(); } catch (IOException e) { log.error("Error closing server socket", e); }
            }

            // Let in-flight requests finish (Http1Connection forces Connection: close once it observes isStopped())
            // before force-closing whatever is still open.
            long deadlineNanos = System.nanoTime() + configuration.getShutdownDrainTimeoutMs() * 1_000_000L;
            while (!activeSockets.isEmpty() && System.nanoTime() < deadlineNanos) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            activeSockets.forEach(s -> { try { s.close(); } catch (IOException ignored) { } });
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(30, TimeUnit.SECONDS))
                    executorService.shutdownNow();
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        });
    }
}
