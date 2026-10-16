package dev.relism.flash;

import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.AnsweredFailure;
import dev.relism.flash.http.HttpException;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.Cors;
import dev.relism.flash.routing.RequestId;
import dev.relism.flash.routing.Middleware;
import dev.relism.flash.sse.SseHandler;
import dev.relism.flash.sse.SseStream;
import dev.relism.flash.websocket.WebSocketFrame;
import dev.relism.flash.websocket.WebSocketHandler;
import dev.relism.flash.websocket.WebSocketSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The app model end to end: what is declared, what the server answers. */
class AppTest {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Server server;

    public record Greeting(String text) {}

    /** Built by the app, its service as a constructor argument. */
    public static final class Greet extends RequestHandler {
        private final Greeting greeting;
        private String computed;
        public Greet(Greeting greeting) { this.greeting = greeting; }
        @Override protected void onInit() { computed = greeting.text() + "!"; }
        @Override protected Object handle(Request request, Response response) { return computed; }
    }

    public static final class Echo extends WebSocketHandler {
        @Override protected void onMessage(WebSocketSession session, WebSocketFrame frame) {}
    }

    @AfterEach
    void stop() {
        if (server != null) server.stop().join();
    }

    private String url(String path) {
        return "http://127.0.0.1:" + server.port() + path;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws Exception {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(url(path))));
    }

    private void start(App app) {
        start(app, ServerConfig.builder().port(0).host("127.0.0.1").shutdownDrainTimeoutMs(100).build());
    }

    private void start(App app, ServerConfig config) {
        server = Server.start(app, config);
    }

    @Test
    void aClassHandlerIsBuiltWithItsServicesAndInitialisedOnce() throws Exception {
        AtomicReference<Greeting> seenAtStart = new AtomicReference<>();
        start(App.create()
                .add(Greeting.class, new Greeting("hello"))
                .route(HttpMethod.GET, "/greet", Greet.class)
                .onStart(services -> seenAtStart.set(services.get(Greeting.class))));

        assertEquals("hello!", get("/greet").body());
        assertEquals("hello", seenAtStart.get().text(), "onStart sees every service built");
    }

    @Test
    void scanFindsHandlersAndSocketsAndRefusesAClassWithTwoRoutes() throws Exception {
        start(App.create().scan("dev.relism.flash.websocket.scantest"));
        assertEquals("http", get("/http").body());

        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> Server.start(App.create().scan("dev.relism.flash.scanbad"), ServerConfig.builder().port(0).build()));
        assertTrue(refused.getMessage().contains("two routing annotations"), refused.getMessage());

        IllegalStateException wrongSse = assertThrows(IllegalStateException.class,
                () -> App.create().scan("dev.relism.flash.scanbadsse"));
        assertTrue(wrongSse.getMessage().contains("not an SseHandler"), wrongSse.getMessage());
    }

    @Test
    void mountMiddlewareAppliesWhateverOrderItIsDeclaredIn() throws Exception {
        Middleware tag = (exchange, next) -> {
            exchange.response().header("X-Scope", "api");
            next.handle(exchange);
        };
        start(App.create().mount("/api", api -> api.get("/x", (req, res) -> "x").use(tag).get("/apiary", (req, res) -> "bees")));

        assertEquals("api", get("/api/x").headers().firstValue("X-Scope").orElse(null));
        assertEquals("bees", get("/api/apiary").body(), "a path that starts like the prefix still gets it");
    }

    @Test
    void headIsServedByTheGetRouteAndOtherMethodsAre405WithAllow() throws Exception {
        start(App.create().get("/thing", (req, res) -> "body").post("/thing", (req, res) -> "made"));

        HttpResponse<String> head = send(HttpRequest.newBuilder(URI.create(url("/thing"))).method("HEAD", HttpRequest.BodyPublishers.noBody()));
        assertEquals(200, head.statusCode());
        assertEquals("", head.body());

        HttpResponse<String> delete = send(HttpRequest.newBuilder(URI.create(url("/thing"))).DELETE());
        assertEquals(405, delete.statusCode());
        assertEquals("GET, POST", delete.headers().firstValue("Allow").orElse(null));

        assertEquals(404, get("/nothing").statusCode());
    }

    @Test
    void corsAnswersAPreflightOnAnyPath() throws Exception {
        start(App.create().use(Cors.allow("https://app.example").headers("Authorization").expose("X-Total"))
                .get("/data", (req, res) -> "d"));

        HttpResponse<String> preflight = send(HttpRequest.newBuilder(URI.create(url("/unrouted")))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://app.example")
                .header("Access-Control-Request-Method", "POST"));
        assertEquals(204, preflight.statusCode());
        assertEquals("https://app.example", preflight.headers().firstValue("Access-Control-Allow-Origin").orElse(null));
        assertEquals("Authorization", preflight.headers().firstValue("Access-Control-Allow-Headers").orElse(null));
        assertTrue(preflight.headers().firstValue("Access-Control-Expose-Headers").isEmpty());

        HttpResponse<String> data = send(HttpRequest.newBuilder(URI.create(url("/data"))).header("Origin", "https://app.example"));
        assertEquals("X-Total", data.headers().firstValue("Access-Control-Expose-Headers").orElse(null));

        HttpResponse<String> foreign = send(HttpRequest.newBuilder(URI.create(url("/data"))).header("Origin", "https://evil.example"));
        assertTrue(foreign.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }

    @Test
    void anHttpExceptionIsAnsweredWithItsStatusAndHeaders() throws Exception {
        start(App.create().get("/private", (req, res) -> {
            throw HttpException.unauthorized().header("WWW-Authenticate", "Bearer");
        }));

        HttpResponse<String> refused = get("/private");
        assertEquals(401, refused.statusCode());
        assertEquals("Bearer", refused.headers().firstValue("WWW-Authenticate").orElse(null));
        assertEquals("{\"error\":\"Unauthorized\",\"status\":401}", refused.body());
    }

    @Test
    void aBodyAboveTheLimitIsRefusedBeforeItIsRead() throws Exception {
        start(App.create().post("/upload", (req, res) -> req.body().bytes().length),
                ServerConfig.builder().port(0).host("127.0.0.1").shutdownDrainTimeoutMs(100).maxBodySize(8).build());

        HttpResponse<String> refused = send(HttpRequest.newBuilder(URI.create(url("/upload")))
                .POST(HttpRequest.BodyPublishers.ofString("more than eight bytes")));
        assertEquals(413, refused.statusCode());
    }

    @Test
    void numbersAreParsedFromTheBytesAndAMalformedOneIsA400() throws Exception {
        start(App.create().get("/items/{id}", (req, res) -> req.paramLong("id") + req.queryInt("page", 1) + ""));

        assertEquals("44", get("/items/42?page=2").body());
        assertEquals("43", get("/items/42").body());
        assertEquals(400, get("/items/4x2").statusCode());
    }

    @Test
    void theClientAddressComesFromForwardedOnlyThroughATrustedProxy() throws Exception {
        App app = App.create().get("/who", (req, res) -> req.clientAddress());
        start(app, ServerConfig.builder().port(0).host("127.0.0.1").shutdownDrainTimeoutMs(100).trustedProxy("127.0.0.1").build());
        assertEquals("203.0.113.9", send(HttpRequest.newBuilder(URI.create(url("/who")))
                .header("X-Forwarded-For", "203.0.113.9, 127.0.0.1")).body());
        server.stop().join();

        start(App.create().get("/who", (req, res) -> req.clientAddress()));
        assertEquals("127.0.0.1", send(HttpRequest.newBuilder(URI.create(url("/who")))
                .header("X-Forwarded-For", "203.0.113.9")).body());
    }

    @Test
    void aSocketRoutesMiddlewareRunsBeforeTheHandshake() {
        Middleware deny = (exchange, next) -> {
            throw HttpException.unauthorized();
        };
        start(App.create().ws("/socket", Echo.class, deny));

        CompletionException failed = assertThrows(CompletionException.class, () -> client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/socket"), new WebSocket.Listener() {})
                .join());
        WebSocketHandshakeException handshake = assertInstanceOf(WebSocketHandshakeException.class, failed.getCause());
        assertEquals(401, handshake.getResponse().statusCode());
    }

    @Test
    void httpAndWebSocketCoexistAtTheSamePath() throws Exception {
        start(App.create().get("/same", (request, response) -> "http").ws("/same", Echo.class));

        assertEquals("http", get("/same").body());
        WebSocket socket = client.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + server.port() + "/same"), new WebSocket.Listener() {})
                .join();
        socket.abort();
    }

    @Test
    void middlewareCanFinishTheSseHandshakeBeforeTheStreamStarts() throws Exception {
        Middleware after = (exchange, next) -> {
            next.handle(exchange);
            exchange.response().header("X-After", "middleware");
        };
        start(App.create().get("/events", new SseHandler() {
            @Override protected void handle(Request request, SseStream stream) throws Exception {
                stream.send("ready");
            }
        }, after));

        HttpResponse<String> response = get("/events");
        assertEquals("middleware", response.headers().firstValue("X-After").orElse(null));
        assertEquals("data: ready\n\n", response.body());
    }

    @Test
    void middlewareCanEncloseTheDeferredHandlerOutermostFirst() throws Exception {
        List<String> order = new java.util.concurrent.CopyOnWriteArrayList<>();
        Middleware outer = (exchange, next) -> {
            next.handle(exchange);
            exchange.around(deferred -> { order.add("outer"); deferred.call(); });
        };
        Middleware inner = (exchange, next) -> {
            next.handle(exchange);
            exchange.around(deferred -> { order.add("inner"); deferred.call(); });
        };
        start(App.create().get("/events", new SseHandler() {
            @Override protected void handle(Request request, SseStream stream) throws Exception {
                stream.send(String.join(",", order));
            }
        }, outer, inner));

        assertEquals("data: outer,inner\n\n", get("/events").body());
    }

    @Test
    void anAnsweredFailureFailsTheScopesAroundTheHandlerButNotTheExchange() throws Exception {
        List<Throwable> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        Middleware scope = (exchange, next) -> {
            next.handle(exchange);
            exchange.around(deferred -> {
                try {
                    deferred.call();
                } catch (AnsweredFailure answered) {
                    seen.add(answered);
                    throw answered;
                }
            });
        };
        start(App.create().get("/events", new SseHandler() {
            @Override protected void handle(Request request, SseStream stream) throws Exception {
                stream.send("told");
                throw new AnsweredFailure(new IllegalStateException("told the peer"));
            }
        }, scope));

        HttpResponse<String> response = get("/events");
        assertEquals(200, response.statusCode());
        assertEquals("data: told\n\n", response.body());
        assertEquals(1, seen.size());
    }

    @Test
    void anSseHandlerCanStillRefuseBeforeItsFirstEvent() throws Exception {
        start(App.create().get("/events", new SseHandler() {
            @Override protected void handle(Request request, SseStream stream) throws Exception {
                if (request.query("limit") == null) throw HttpException.badRequest("limit is required");
                stream.send("ok");
            }
        }));

        HttpResponse<String> refused = get("/events");
        assertEquals(400, refused.statusCode());
        assertEquals("{\"error\":\"limit is required\",\"status\":400}", refused.body());
        assertEquals("data: ok\n\n", get("/events?limit=1").body());
    }

    @Test
    void aRequestIdIsEchoedWhenWellFormedAndMadeOtherwiseOnEveryAnswer() throws Exception {
        start(App.create().use(RequestId.header("X-Request-Id"))
                .get("/ok", (req, res) -> "ok")
                .get("/refused", (req, res) -> { throw HttpException.conflict("no"); }));

        assertEquals("abc-123-X", send(HttpRequest.newBuilder(URI.create(url("/ok"))).header("X-Request-Id", "abc-123-X"))
                .headers().firstValue("X-Request-Id").orElse(null));
        String made = send(HttpRequest.newBuilder(URI.create(url("/ok"))).header("X-Request-Id", "bad_id"))
                .headers().firstValue("X-Request-Id").orElse("");
        assertTrue(made.matches("[0-9a-f]+-[0-9a-f]+"), made);
        assertNotEquals(made, get("/ok").headers().firstValue("X-Request-Id").orElse(null));
        assertTrue(get("/refused").headers().firstValue("X-Request-Id").isPresent());
        assertTrue(get("/missing").headers().firstValue("X-Request-Id").isPresent());
    }

    @Test
    void anAppRunsOnOneServer() {
        App app = App.create().get("/", (req, res) -> "ok");
        start(app);

        assertThrows(IllegalStateException.class, () -> Server.start(app, ServerConfig.builder().port(0).build()));
    }
}
