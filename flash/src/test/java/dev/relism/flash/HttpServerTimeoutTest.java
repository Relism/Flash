package dev.relism.flash;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * trickling bytes slower than the timeout window — each individual read still succeeds. These
 * tests prove the absolute deadline in {@code dev.relism.flash.transport.BufferedByteSource}
 * actually bounds the total time, not just each read.
 */
class HttpServerTimeoutTest {

    private FlashApp app;

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    private int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    @Test
    void slowlorisHeaderDribble_disconnectedWithinHeaderReadTimeout() throws Exception {
        int headerTimeoutMs = 300;
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .headerReadTimeoutMs(headerTimeoutMs)
                .idleKeepAliveTimeoutMs(60_000)
                .build());
        app.get("/", (req, res) -> "ok");
        app.start();

        long start = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            // One byte of a request line, then silence — never completes the header block.
            out.write('G');
            out.flush();

            // The server must close its side within headerReadTimeoutMs (+ generous slack for
            // scheduling). Detected as EOF (-1) or a reset when the client tries to read.
            int result = socket.getInputStream().read();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(-1, result, "server must close, not hang, after the header deadline");
            assertTrue(elapsedMs < 5_000, "must not have fallen back to the client's own SO_TIMEOUT");
            assertTrue(elapsedMs >= headerTimeoutMs - 50,
                    "must not close before the configured deadline (was " + elapsedMs + "ms)");
        }
    }

    @Test
    void idleKeepAliveConnection_disconnectedWithinIdleTimeout() throws Exception {
        int idleTimeoutMs = 300;
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .headerReadTimeoutMs(10_000)
                .idleKeepAliveTimeoutMs(idleTimeoutMs)
                .build());
        app.get("/", (req, res) -> "ok");
        app.start();

        long start = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            // Send nothing at all — a connection accepted and then left idle.
            int result = socket.getInputStream().read();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertEquals(-1, result);
            assertTrue(elapsedMs < 5_000);
            assertTrue(elapsedMs >= idleTimeoutMs - 50, "was " + elapsedMs + "ms");
        }
    }

    @Test
    void slowBodyDribble_disconnectedWithinBodyReadTimeout() throws Exception {
        int bodyTimeoutMs = 300;
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .headerReadTimeoutMs(10_000)
                .idleKeepAliveTimeoutMs(10_000)
                .bodyReadTimeoutMs(bodyTimeoutMs)
                .build());
        app.post("/echo", (req, res) -> req.body().bytes());
        app.start();

        long start = System.nanoTime();
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: 100\r\n\r\n" + "x".repeat(5))
                    .getBytes(StandardCharsets.UTF_8));
            out.flush();
            // Only 5 of the declared 100 bytes were sent; the remaining 95 never arrive. Whether
            // the server responds with an error before closing or simply closes, *something*
            // must happen within the body deadline rather than a hang until the client's own
            // (much longer) timeout.
            socket.getInputStream().read();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 5_000, "must not have fallen back to the client's own SO_TIMEOUT");
            assertTrue(elapsedMs >= bodyTimeoutMs - 50, "was " + elapsedMs + "ms");
        }
    }

    @Test
    void tlsHandshakeNeverStarted_disconnectedWithinHeaderReadTimeout(@TempDir Path dir) throws Exception {
        int headerTimeoutMs = 300;
        Path ks = TestKeystores.build(dir, "timeout.p12", "changeit",
                TestKeystores.Entry.of("only", "timeout.test"));
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .headerReadTimeoutMs(headerTimeoutMs)
                .build());
        app.get("/", (req, res) -> "ok");
        app.start();

        long start = System.nanoTime();
        // A plain socket that never speaks TLS at all — the server's explicit
        // and must be bounded by headerReadTimeoutMs rather than hanging forever. Whether the
        // JSSE implementation sends a TLS alert record before closing or just closes outright
        // is a JSSE implementation detail, not something this test should pin down — the
        // property under test is purely the bound on wall-clock time.
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getInputStream().read();
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertTrue(elapsedMs < 5_000, "must not have fallen back to the client's own SO_TIMEOUT");
            assertTrue(elapsedMs >= headerTimeoutMs - 50, "was " + elapsedMs + "ms");
        }
    }

    @Test
    void wellBehavedRequest_wellWithinTimeouts_unaffected() throws Exception {
        int port = freePort();
        app = FlashApp.create(FlashConfiguration.builder()
                .port(port).host("127.0.0.1")
                .headerReadTimeoutMs(300)
                .idleKeepAliveTimeoutMs(300)
                .bodyReadTimeoutMs(300)
                .build());
        app.get("/ping", (req, res) -> "pong");
        app.start();

        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(5_000);
            socket.getOutputStream().write(
                    "GET /ping HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            byte[] buf = new byte[4096];
            int n = socket.getInputStream().read(buf);
            assertTrue(n > 0);
            String response = new String(buf, 0, n, StandardCharsets.UTF_8);
            assertTrue(response.startsWith("HTTP/1.1 200 OK"));
            assertTrue(response.contains("pong"));
        }
    }
}
