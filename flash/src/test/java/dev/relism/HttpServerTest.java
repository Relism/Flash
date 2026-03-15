package dev.relism;

import dev.relism.http.ContentType;
import dev.relism.models.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class HttpServerTest {

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        // Find a free ephemeral port
        try (ServerSocket s = new ServerSocket(0)) {
            port = s.getLocalPort();
        }

        HttpServerConfiguration config = HttpServerConfiguration.builder()
                .port(port)
                .host("127.0.0.1")
                .build();

        server = new HttpServer(config);
        
        // Setup some routes
        server.get("/api/ping", (req, res) -> "pong");
        
        server.post("/api/echo", (req, res) -> {
            byte[] body = req.getBody();
            return new Response(201, body, ContentType.TEXT_PLAIN); // echo body & change status
        });
        
        server.get("/api/crash", (req, res) -> {
            throw new RuntimeException("Simulated Crash");
        });

        server.start().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    // --- raw socket helper ---

    private String sendRawRequest(String rawHttp) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port);
             OutputStream out = socket.getOutputStream();
             InputStream in = socket.getInputStream()) {

            out.write(rawHttp.getBytes(StandardCharsets.UTF_8));
            out.flush();

            // Read until \r\n\r\n to get the full header block
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int b, prev3 = -1, prev2 = -1, prev1 = -1;
            while ((b = in.read()) != -1) {
                headerBuf.write(b);
                if (prev3 == '\r' && prev2 == '\n' && prev1 == '\r' && b == '\n') break;
                prev3 = prev2; prev2 = prev1; prev1 = b;
            }
            String headers = headerBuf.toString(StandardCharsets.UTF_8);

            // Parse Content-Length
            int contentLength = 0;
            for (String line : headers.split("\r\n")) {
                if (line.toLowerCase().startsWith("content-length:")) {
                    contentLength = Integer.parseInt(line.substring(line.indexOf(':') + 1).trim());
                    break;
                }
            }

            // Read exactly Content-Length bytes for the body
            byte[] body = in.readNBytes(contentLength);
            return headers + new String(body, StandardCharsets.UTF_8);
        }
    }

    // --- E2E Tests ---

    @Test
    void testGet_pingRoute_returns200AndStringBody() throws Exception {
        String req = "GET /api/ping HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "\r\n";
                     
        String res = sendRawRequest(req);
        
        assertTrue(res.startsWith("HTTP/1.1 200 OK"));
        assertTrue(res.contains("Content-Length: 4")); // "pong"
        assertTrue(res.endsWith("pong"));
    }

    @Test
    void testPost_echoRoute_returns201AndEchoesBody() throws Exception {
        String body = "Hello, Flash!";
        String req = "POST /api/echo HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "Content-Length: " + body.length() + "\r\n" +
                     "\r\n" +
                     body;
                     
        String res = sendRawRequest(req);
        
        assertTrue(res.startsWith("HTTP/1.1 201 Created"));
        assertTrue(res.contains("Content-Length: " + body.length()));
        assertTrue(res.endsWith(body));
    }

    @Test
    void testNotFound_returns404Html() throws Exception {
        String req = "GET /api/unknown HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "\r\n";
                     
        String res = sendRawRequest(req);
        
        assertTrue(res.startsWith("HTTP/1.1 404 Not Found"));
        assertTrue(res.contains("404"));
        assertTrue(res.contains("No route matched this request"));
    }

    @Test
    void testException_returns500Html() throws Exception {
        String req = "GET /api/crash HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "\r\n";
                     
        String res = sendRawRequest(req);
        
        assertTrue(res.startsWith("HTTP/1.1 500 Internal Server Error"));
        assertTrue(res.contains("500"));
        assertTrue(res.contains("Simulated Crash"));
    }

    @Test
    void testRoot_returns404_noExceptionTossed() throws Exception {
        String req = "GET / HTTP/1.1\r\n" +
                     "Host: localhost\r\n" +
                     "\r\n";
                     
        String res = sendRawRequest(req);
        
        assertTrue(res.startsWith("HTTP/1.1 404 Not Found"));
        assertTrue(res.contains("No route matched this request."));
    }
}
