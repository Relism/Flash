package dev.relism.flash;

import dev.relism.flash.extension.FlashApp;
import dev.relism.flash.extension.FlashConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerTest {

    private FlashApp app;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        app = FlashApp.create(FlashConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .build());

        app.get("/api/ping", (req, res) -> "pong");

        app.post("/api/echo", (req, res) -> {
            byte[] body = req.body().bytes();
            return res.status(201).body(body);
        });

        app.get("/api/crash", (req, res) -> {
            throw new RuntimeException("Simulated Crash");
        });

        byte[] streamData = "streaming response body".getBytes(StandardCharsets.UTF_8);
        app.get("/api/stream", (req, res) ->
                res.stream(new ByteArrayInputStream(streamData), streamData.length));

        app.get("/api/chunked-out", (req, res) ->
                res.chunked(new ByteArrayInputStream(streamData)));

        app.get("/api/custom-header", (req, res) ->
                res.body("ok").header("X-Flash", "works"));

        app.post("/api/chunked-in", (req, res) -> {
            byte[] body = req.body().bytes();
            return res.body(body);
        });

        app.start();
    }

    @AfterEach
    void tearDown() {
        if (app != null) app.stop();
    }

    // --- helpers ---

    private static final int SOCKET_TIMEOUT_MS = 5000;

    private String sendRawRequest(String rawHttp) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            out.write(rawHttp.getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readOneResponse(in);
        }
    }

    private String readOneResponse(InputStream in) throws Exception {
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int b, prev3 = -1, prev2 = -1, prev1 = -1;
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
            prev3 = prev2; prev2 = prev1; prev1 = b;
        }
        String headers = headerBuf.toString(StandardCharsets.UTF_8);

        String body;
        if (headers.toLowerCase().contains("transfer-encoding: chunked")) {
            body = readChunkedBody(in);
        } else {
            int contentLength = 0;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    break;
                }
            }
            body = new String(in.readNBytes(contentLength), StandardCharsets.UTF_8);
        }
        return headers + body;
    }

    private String readChunkedBody(InputStream in) throws Exception {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        while (true) {
            StringBuilder sizeLine = new StringBuilder();
            int b;
            while ((b = in.read()) != -1 && b != '\n') {
                if (b != '\r') sizeLine.append((char) b);
            }
            int size = Integer.parseInt(sizeLine.toString().trim(), 16);
            if (size == 0) break;
            result.write(in.readNBytes(size));
            in.read(); in.read();
        }
        return result.toString(StandardCharsets.UTF_8);
    }

    // --- E2E Tests ---

    @Test
    void testGet_pingRoute_returns200AndStringBody() throws Exception {
        String req = "GET /api/ping HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 200 OK"));
        assertTrue(res.contains("Content-Length: 4"));
        assertTrue(res.endsWith("pong"));
    }

    @Test
    void testPost_echoRoute_returns201AndEchoesBody() throws Exception {
        String body = "Hello, Flash!";
        String req = "POST /api/echo HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length() + "\r\n\r\n" + body;
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 201 Created"));
        assertTrue(res.contains("Content-Length: " + body.length()));
        assertTrue(res.endsWith(body));
    }

    @Test
    void testNotFound_returns404() throws Exception {
        String req = "GET /api/unknown HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 404 Not Found"));
        // Prod default: JSON body — no internal details leaked
        assertTrue(res.contains("\"status\":404"));
        assertTrue(res.contains("application/json"));
    }

    @Test
    void testException_returns500() throws Exception {
        String req = "GET /api/crash HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 500 Internal Server Error"));
        // Prod default: generic JSON — stack trace not leaked
        assertTrue(res.contains("\"status\":500"));
        assertTrue(res.contains("application/json"));
    }

    @Test
    void testRoot_returns404() throws Exception {
        String req = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 404 Not Found"));
        assertTrue(res.contains("\"status\":404"));
    }

    @Test
    void testStreamingResponse_writesContentLengthAndBody() throws Exception {
        String req = "GET /api/stream HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 200 OK"));
        assertTrue(res.contains("Content-Length: 23"));
        assertTrue(res.endsWith("streaming response body"));
    }

    @Test
    void testChunkedResponse_writesTransferEncodingChunked() throws Exception {
        String req = "GET /api/chunked-out HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 200 OK"));
        assertTrue(res.contains("Transfer-Encoding: chunked"));
        assertTrue(res.endsWith("streaming response body"));
    }

    @Test
    void testCustomHeader_appearsInResponse() throws Exception {
        String req = "GET /api/custom-header HTTP/1.1\r\nHost: localhost\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 200 OK"));
        assertTrue(res.contains("X-Flash: works\r\n"));
    }

    @Test
    void testChunkedRequestBody_decodedAndEchoed() throws Exception {
        String req = "POST /api/chunked-in HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                   + "5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n";
        String res = sendRawRequest(req);
        assertTrue(res.startsWith("HTTP/1.1 200 OK"));
        assertTrue(res.endsWith("hello world"));
    }

    @Test
    void testKeepAlive_twoRequestsOnSameConnection() throws Exception {
        String req = "GET /api/ping HTTP/1.1\r\nHost: localhost\r\n\r\n";

        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {
            socket.setSoTimeout(SOCKET_TIMEOUT_MS);
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.write(req.getBytes(StandardCharsets.UTF_8));
            out.flush();

            String first  = readOneResponse(in);
            String second = readOneResponse(in);

            assertTrue(first.contains("pong"));
            assertTrue(second.contains("pong"));
            assertTrue(first.contains("Connection: keep-alive"));
            assertTrue(second.contains("Connection: keep-alive"));
        }
    }
}
