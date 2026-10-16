package dev.relism.flash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.relism.flash.bytes.ByteWriter;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http2.Http2Preface;
import dev.relism.flash.http2.hpack.HpackEncoder;
import dev.relism.flash.routing.Route;
import dev.relism.flash.routing.RouteHook;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Methods as tokens: {@code app.any}, {@code @AnyMethod}, the rest of {@code **}, and 501 for a method nothing takes. */
class AnyMethodTest {

    private Server server;
    private final HttpClient client = HttpClient.newHttpClient();

    @AfterEach
    void stop() {
        client.close();
        if (server != null) server.stop().join();
    }

    private void start(App app) {
        server = Server.start(app, ServerConfig.builder().host("127.0.0.1").port(0).http2CleartextEnabled(true).build());
    }

    private HttpResponse<String> send(String method, String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path))
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Object echo(Request request, Response response) {
        response.header("x-route", "any");
        return request.requestLine().getMethodName() + " " + request.param("**");
    }

    @Test
    void oneRouteAnswersEveryMethodAndNamesTheRest() throws Exception {
        start(App.create().any("/files/**", AnyMethodTest::echo)
                .mount("/api", api -> api.any("/dav/**", AnyMethodTest::echo)));

        for (String method : List.of("GET", "POST", "DELETE", "PROPFIND", "MKCOL", "VERSION-CONTROL"))
            assertEquals(method + " a/b%20c.txt", send(method, "/files/a/b%20c.txt").body()); // as sent, like every parameter
        assertEquals("PROPFIND ", send("PROPFIND", "/files").body());
        assertEquals("COPY x", send("COPY", "/api/dav/x").body());
    }

    @Test
    void aRouteOfItsOwnMethodWinsAndHeadFollowsGet() throws Exception {
        start(App.create().any("/files/**", AnyMethodTest::echo)
                .get("/files/readme", (req, res) -> { res.header("x-route", "get"); return "readme"; }));

        assertEquals("readme", send("GET", "/files/readme").body());
        assertEquals("PUT readme", send("PUT", "/files/readme").body());
        HttpResponse<String> head = send("HEAD", "/files/readme");
        assertEquals("get", head.headers().firstValue("x-route").orElse(null));
        assertEquals("any", send("HEAD", "/files/other").headers().firstValue("x-route").orElse(null));
    }

    @Test
    void aMethodNothingTakesIs501AndTheConnectionStaysUsable() throws Exception {
        start(App.create().get("/data", (req, res) -> "d"));

        assertEquals(501, send("BREW", "/data").statusCode());
        assertEquals(501, send("PROPFIND", "/elsewhere").statusCode());
        assertEquals(405, send("POST", "/data").statusCode());
        assertEquals("GET", send("POST", "/data").headers().firstValue("allow").orElse(null));

        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            OutputStream out = socket.getOutputStream();
            out.write("BREW /data HTTP/1.1\r\nHost: a\r\n\r\nGET /data HTTP/1.1\r\nHost: a\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            String both = readUntil(socket, "\r\n\r\nd");
            assertEquals(true, both.startsWith("HTTP/1.1 501"), both);
            assertEquals(true, both.contains("HTTP/1.1 200"), both);
        }
    }

    @Test
    void withAnAnyRouteAnUnknownMethodElsewhereIsAnOrdinaryMiss() throws Exception {
        start(App.create().any("/files/**", AnyMethodTest::echo).get("/data", (req, res) -> "d"));

        assertEquals(404, send("PROPFIND", "/nothing").statusCode());
        assertEquals(405, send("PROPFIND", "/data").statusCode());
    }

    @Test
    void anAnyMethodAnnotationIsScannedAsAnAnyRouteWithNoMethod() throws Exception {
        List<Route> routes = new ArrayList<>();
        App app = App.create().scan("dev.relism.flash.scanany");
        app.add(RouteHook.class, new RouteHook() {
            @Override public void routed(Route route) { routes.add(route); }
        });
        start(app);

        assertEquals("LOCK x/y", send("LOCK", "/scanned/x/y").body());
        assertEquals(1, routes.size());
        assertNull(routes.get(0).method());
        assertEquals("/scanned/**", routes.get(0).path());
    }

    @Test
    void aWebSocketRouteOnTheSamePathStillWinsAndAnUpgradeElsewhereIsTheGetItIs() throws Exception {
        WebSocketHandler socket = new WebSocketHandler() {
            @Override protected void onMessage(WebSocketSession session, WebSocketFrame frame) throws Exception {
                session.sendText("socket");
            }
        };
        start(App.create().any("/live/**", AnyMethodTest::echo).ws("/live/socket", socket));

        CompletableFuture<String> answer = new CompletableFuture<>();
        WebSocket ws = client.newWebSocketBuilder().buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/live/socket"),
                new WebSocket.Listener() {
                    @Override public java.util.concurrent.CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                        answer.complete(data.toString());
                        return null;
                    }
                }).join();
        ws.sendText("hi", true);
        assertEquals("socket", answer.get(5, TimeUnit.SECONDS));
        ws.abort();

        try (Socket raw = new Socket("127.0.0.1", server.port())) {
            raw.getOutputStream().write(("GET /live/other HTTP/1.1\r\nHost: a\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            String answered = readUntil(raw, "GET other");
            assertEquals(true, answered.startsWith("HTTP/1.1 200") && answered.contains("GET other"), answered);
        }
    }

    @Test
    void http2CarriesAnyTokenAndResetsAStreamWhoseMethodIsNone() throws Exception {
        start(App.create().any("/files/**", AnyMethodTest::echo));

        try (Socket socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(Http2Preface.clientPreface());
            out.write(frame(4, 0, 0, new byte[0]));
            out.write(frame(1, 0x5, 1, request("PROPFIND", "/files/x")));
            out.write(frame(1, 0x5, 3, request("PRO(FIND", "/files/x")));
            out.flush();

            InputStream in = socket.getInputStream();
            String body = null;
            boolean reset = false;
            while (body == null || !reset) {
                byte[] header = in.readNBytes(9);
                int length = ((header[0] & 0xFF) << 16) | ((header[1] & 0xFF) << 8) | (header[2] & 0xFF);
                byte[] payload = in.readNBytes(length);
                int stream = ByteBuffer.wrap(header, 5, 4).getInt() & 0x7FFFFFFF;
                if (header[3] == 0 && stream == 1 && length > 0) body = new String(payload, StandardCharsets.UTF_8);
                if (header[3] == 3 && stream == 3) reset = true;
                if (header[3] == 4 && (header[4] & 1) == 0) out.write(frame(4, 1, 0, new byte[0]));
            }
            assertEquals("PROPFIND x", body);
        }
    }

    private static byte[] request(String method, String path) {
        ByteWriter block = new ByteWriter(64);
        HpackEncoder.writeLiteralWithNameIndex(block, 2, method.getBytes(StandardCharsets.US_ASCII), false);
        HpackEncoder.writeIndexed(block, 6); // :scheme http
        HpackEncoder.writeLiteralWithNameIndex(block, 4, path.getBytes(StandardCharsets.US_ASCII), false);
        HpackEncoder.writeLiteralWithNameIndex(block, 1, "localhost".getBytes(StandardCharsets.US_ASCII), false);
        return Arrays.copyOf(block.array(), block.length());
    }

    private static byte[] frame(int type, int flags, int stream, byte[] payload) {
        return ByteBuffer.allocate(9 + payload.length).put((byte) (payload.length >> 16)).put((byte) (payload.length >> 8))
                .put((byte) payload.length).put((byte) type).put((byte) flags).putInt(stream).put(payload).array();
    }

    /** What the server wrote until {@code needle} appeared. */
    private static String readUntil(Socket socket, String needle) throws Exception {
        socket.setSoTimeout(5_000);
        StringBuilder read = new StringBuilder();
        InputStream in = socket.getInputStream();
        while (read.indexOf(needle) < 0) {
            int b = in.read();
            if (b < 0) break;
            read.append((char) b);
        }
        return read.toString();
    }
}
