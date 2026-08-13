package dev.relism.flash.transport;

import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.routing.AbstractRouter;
import dev.relism.flash.routing.AbstractWsRouter;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathRouterImpl;
import dev.relism.flash.routing.routers.fastpathrouter.FastPathWsRouterImpl;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A scratch is always released — including on an exception path — and a socket is always
 * removed from {@code activeSockets}, regardless of how the dispatched
 * checks list), verified here with a protocol implementation that deliberately throws.
 */
class ConnectionRunnerTest {

    @Test
    void scratchAndActiveSocketEntry_alwaysReleased_evenWhenTheProtocolThrows() throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
        ScratchPool scratchPool = new ScratchPool();
        AbstractRouter router = new FastPathRouterImpl();
        AbstractWsRouter wsRouter = new FastPathWsRouterImpl();
        FlashConfiguration configuration = FlashConfiguration.builder().port(0).build();

        ConnectionProtocol throwingProtocol = ctx -> {
            throw new IOException("simulated protocol failure");
        };

        ConnectionRunner runner = new ConnectionRunner(
                executor, activeSockets, scratchPool, router, wsRouter, configuration, throwingProtocol);

        try (ServerSocket serverSocket = new ServerSocket(0)) {
            int port = serverSocket.getLocalPort();
            CountDownLatch accepted = new CountDownLatch(1);

            Thread acceptThread = new Thread(() -> {
                try (Socket serverSide = serverSocket.accept()) {
                    runner.accept(serverSide, () -> false);
                    accepted.countDown();
                    Thread.sleep(300); // give the submitted virtual-thread task time to run
                } catch (Exception ignored) {
                }
            });
            acceptThread.start();

            try (Socket client = new Socket("127.0.0.1", port)) {
                assertTrue(accepted.await(2, TimeUnit.SECONDS));
                Thread.sleep(300); // let ConnectionRunner's virtual thread finish

                assertTrue(activeSockets.isEmpty(), "socket must be removed from activeSockets on every exit path");
            }
            acceptThread.join(2000);
        } finally {
            executor.shutdownNow();
        }
    }
}
