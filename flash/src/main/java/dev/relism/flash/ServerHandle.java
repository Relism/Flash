package dev.relism.flash;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.AbstractWsRouter;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Public handle to the underlying HTTP transport. Returned by {@link #create}
 * so that {@link FlashApp} can start and stop the server
 * without holding a direct reference to the package-private {@link HttpServer}.
 */
public interface ServerHandle {

    /** Starts the accept loop on a background platform thread. Returns immediately. */
    void start();

    /**
     * Starts the accept loop and blocks the calling thread until the server is stopped.
     * Suitable for use in {@code main()} when no other work is needed after startup.
     */
    void startAndBlock();

    /** Gracefully stops the server, draining active connections. */
    CompletableFuture<Void> stop();

    static ServerHandle create(FlashConfiguration config,
                               AbstractRouter httpRouter,
                               AbstractWsRouter wsRouter) throws IOException {
        return new HttpServer(config, httpRouter, wsRouter);
    }
}
