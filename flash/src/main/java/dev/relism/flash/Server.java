package dev.relism.flash;

import dev.relism.flash.routing.Router;
import dev.relism.flash.transport.ServerLifecycle;
import dev.relism.flash.transport.TransportFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * One {@link App} served on the listeners of a {@link ServerConfig}.
 *
 * <p>{@link #start} builds every service and handler, binds the listeners, runs the app's
 * {@code onStart} work and only then accepts connections, so a broken app never opens a port.
 * {@link #stop} drains in-flight requests and then closes the app's services.
 */
public final class Server {

    private final App app;
    private final ServerLifecycle lifecycle;

    private Server(App app, ServerLifecycle lifecycle) {
        this.app = app;
        this.lifecycle = lifecycle;
    }

    public static Server start(App app, ServerConfig config) {
        Router router = app.compile(config);
        ServerLifecycle lifecycle;
        try {
            lifecycle = TransportFactory.create(config, router);
            app.started();
        } catch (IOException unbound) {
            app.services().close();
            throw new UncheckedIOException("Cannot bind " + config.getListeners() + " / port " + config.getPort(), unbound);
        } catch (RuntimeException failed) {
            app.services().close();
            throw failed;
        }
        lifecycle.start();
        return new Server(app, lifecycle);
    }

    /** Port of the first listener; the one the OS picked when the config asked for port 0. */
    public int port() { return lifecycle.port(); }

    public List<Integer> ports() { return lifecycle.ports(); }

    /** Stops accepting, drains in-flight requests, then closes the app's services. */
    public CompletableFuture<Void> stop() {
        return lifecycle.stop().whenComplete((ignored, failure) -> app.services().close());
    }

    /** Blocks until the server stops: the end of a {@code main}. */
    public void await() {
        lifecycle.await();
    }
}
