package dev.relism.flash.transport;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * finish (forced to {@code Connection: close}), then force-close whatever remains after
 * {@code shutdownDrainTimeoutMs}.
 */
class ServerLifecycleGracefulShutdownTest {

    private FlashApp app;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void inFlightRequest_completesWithConnectionClose_duringShutdown() throws Exception {
        int port = freePort();
        CountDownLatch handlerStarted  = new CountDownLatch(1);
        CountDownLatch releaseHandler  = new CountDownLatch(1);

        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .shutdownDrainTimeoutMs(5_000)
                .build());
        app.get("/slow", (req, res) -> {
            handlerStarted.countDown();
            assertTrue(releaseHandler.await(5, TimeUnit.SECONDS));
            return "done";
        });
        app.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write("GET /slow HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();

            assertTrue(handlerStarted.await(2, TimeUnit.SECONDS));

            // Begin shutdown while the handler is still running.
            CompletableFuture<Void> stopping = app.stop();
            // Give stop() a moment to mark the server as stopping and close the listener.
            Thread.sleep(100);
            releaseHandler.countDown();

            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            String response = new String(buf, 0, n, StandardCharsets.UTF_8);

            assertTrue(response.startsWith("HTTP/1.1 200 OK"), response);
            assertTrue(response.contains("done"), response);
            // though the client asked for HTTP/1.1's default keep-alive.
            assertTrue(response.contains("Connection: close"), response);

            stopping.get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void stop_closesListener_soNewConnectionsAreRefused() throws Exception {
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .shutdownDrainTimeoutMs(500)
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        // Confirm the server actually answers before stopping it.
        try (Socket probe = new Socket("127.0.0.1", port)) {
            probe.setSoTimeout(2_000);
            probe.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.UTF_8));
            probe.getOutputStream().flush();
            assertTrue(probe.getInputStream().read() != -1);
        }

        app.stop().get(5, TimeUnit.SECONDS);

        assertThrows(Exception.class, () -> {
            try (Socket socket = new Socket()) {
                socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
                socket.setSoTimeout(500);
                socket.getOutputStream().write("GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n"
                        .getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
                int result = socket.getInputStream().read();
                if (result == -1) throw new java.io.IOException("connection refused/closed, as expected");
            }
        });
    }
}
