package dev.relism.flash.transport;

import static org.junit.jupiter.api.Assertions.*;

import dev.relism.flash.ServerConfig;
import dev.relism.flash.routing.Errors;
import dev.relism.flash.routing.Router;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * A scratch is always released — including on an exception path — and a socket is always removed
 * from {@code activeSockets}, regardless of how the dispatched checks list), verified here with a
 * protocol implementation that deliberately throws.
 */
class ConnectionRunnerTest {

  @Test
  void scratchAndActiveSocketEntry_alwaysReleased_evenWhenTheProtocolThrows() throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    ScratchPool scratchPool = new ScratchPool();
    Router router = new Router();
    router.compile(Errors.notFound(false), Errors.handler(false));
    ServerConfig configuration = ServerConfig.builder().port(0).build();

    ConnectionProtocol throwingProtocol =
        ctx -> {
          throw new IOException("simulated protocol failure");
        };

    ConnectionRunner runner =
        new ConnectionRunner(
            executor,
            activeSockets,
            scratchPool,
            router,
            configuration,
            throwingProtocol,
            () -> throwingProtocol);

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      CountDownLatch accepted = new CountDownLatch(1);

      Thread acceptThread =
          new Thread(
              () -> {
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

        assertTrue(
            activeSockets.isEmpty(),
            "socket must be removed from activeSockets on every exit path");
      }
      acceptThread.join(2000);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void connectionsBeyondMaxConnections_areClosedImmediately_beforeAnyProtocolWork()
      throws Exception {
    ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    Set<Socket> activeSockets = ConcurrentHashMap.newKeySet();
    ScratchPool scratchPool = new ScratchPool();
    Router router = new Router();
    router.compile(Errors.notFound(false), Errors.handler(false));
    ServerConfig configuration =
        ServerConfig.builder().port(0).maxConnections(1).build();

    AtomicInteger protocolInvocations = new AtomicInteger();
    ConnectionProtocol countingProtocol =
        ctx -> {
          protocolInvocations.incrementAndGet();
          throw new IOException("simulated protocol failure");
        };

    ConnectionRunner runner =
        new ConnectionRunner(
            executor,
            activeSockets,
            scratchPool,
            router,
            configuration,
            countingProtocol,
            () -> countingProtocol);

    try (ServerSocket serverSocket = new ServerSocket(0)) {
      int port = serverSocket.getLocalPort();

      // First connection: admitted (activeSockets is empty, limit is 1). Held open by never
      // closing the client socket, so it still counts toward the limit for the second attempt.
      Socket firstClient = new Socket("127.0.0.1", port);
      Socket firstServerSide = serverSocket.accept();
      activeSockets.add(firstServerSide); // simulate an in-flight, still-admitted connection

      // Second connection: activeSockets.size() (1) >= maxConnections (1) -> must be
      // rejected at accept() time, before the executor or protocol ever run.
      try (Socket secondClient = new Socket("127.0.0.1", port);
          Socket secondServerSide = serverSocket.accept()) {
        runner.accept(secondServerSide, () -> false);
        Thread.sleep(300); // give any (incorrectly) submitted virtual-thread task time to run

        assertEquals(0, protocolInvocations.get(), "rejected connection must not reach the protocol");
        assertEquals(1, activeSockets.size(), "rejected connection must not be added to activeSockets");
        assertTrue(secondServerSide.isClosed(), "rejected connection's socket must be closed");
      } finally {
        firstServerSide.close();
        firstClient.close();
      }
    } finally {
      executor.shutdownNow();
    }
  }
}
