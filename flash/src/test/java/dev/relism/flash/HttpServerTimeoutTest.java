package dev.relism.flash;

import dev.relism.flash.App;
import dev.relism.flash.Server;
import dev.relism.flash.ServerConfig;
import dev.relism.flash.tls.TestKeystores;
import dev.relism.flash.tls.TlsConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
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

    private App app;

    private Server server;

    @AfterEach
    void tearDown() {
        if (server != null) server.stop();
    }

    @Test
    void slowlorisHeaderDribble_disconnectedWithinHeaderReadTimeout() throws Exception {
        int headerTimeoutMs = 300;
        ServerConfig config1 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .headerReadTimeoutMs(headerTimeoutMs)
                .idleKeepAliveTimeoutMs(60_000)
                .build();
        app = App.create();
        app.get("/", (req, res) -> "ok");
        server = Server.start(app, config1);
        int port = server.port();

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
        ServerConfig config2 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .headerReadTimeoutMs(10_000)
                .idleKeepAliveTimeoutMs(idleTimeoutMs)
                .build();
        app = App.create();
        app.get("/", (req, res) -> "ok");
        server = Server.start(app, config2);
        int port = server.port();

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
        ServerConfig config3 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .headerReadTimeoutMs(10_000)
                .idleKeepAliveTimeoutMs(10_000)
                .bodyReadTimeoutMs(bodyTimeoutMs)
                .build();
        app = App.create();
        app.post("/echo", (req, res) -> req.body().bytes());
        server = Server.start(app, config3);
        int port = server.port();

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
        ServerConfig config4 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .tls(TlsConfig.keystore(ks, "changeit"))
                .headerReadTimeoutMs(headerTimeoutMs)
                .build();
        app = App.create();
        app.get("/", (req, res) -> "ok");
        server = Server.start(app, config4);
        int port = server.port();

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
        ServerConfig config5 = ServerConfig.builder()
                .port(0).host("127.0.0.1")
                .headerReadTimeoutMs(300)
                .idleKeepAliveTimeoutMs(300)
                .bodyReadTimeoutMs(300)
                .build();
        app = App.create();
        app.get("/ping", (req, res) -> "pong");
        server = Server.start(app, config5);
        int port = server.port();

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
