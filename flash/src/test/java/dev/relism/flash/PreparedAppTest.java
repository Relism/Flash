package dev.relism.flash;

import org.junit.jupiter.api.Test;
import dev.relism.flash.http.RequestHandler;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.sse.SseHandler;
import dev.relism.flash.sse.SseStream;
import dev.relism.flash.routing.Middleware;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class PreparedAppTest {
    @Test void embeddedRoutesKeepParametersAndCloseTheirOwnServices() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        class Lifetime implements AutoCloseable { public void close() { closed.set(true); } }
        var config = ServerConfig.builder().host("127.0.0.1").port(0).build();
        try (PreparedApp child = App.create().add(Lifetime.class, new Lifetime())
                .get("/plugins/{key}/{id}", (req, res) -> req.param("key") + ":" + req.param("id"))
                .prepare(config); HttpClient client = HttpClient.newHttpClient()) {
            Server server = Server.start(App.create().any("/plugins/**", (req, res) -> res,
                    (exchange, next) -> child.handle(exchange)), config);
            try {
                var result = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/plugins/demo/42")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, result.statusCode());
                assertEquals("demo:42", result.body());
                assertFalse(closed.get());
            } finally { server.stop().join(); }
            assertFalse(closed.get());
        }
        assertTrue(closed.get());
    }
    @Test void embeddedStreamRunsOnceInsideBothApplicationsScopes() throws Exception {
        var order = new ArrayList<String>();
        var calls = new AtomicInteger();
        Middleware inner = (exchange, next) -> {
            next.handle(exchange);
            exchange.around(body -> { order.add("inner"); body.call(); });
        };
        var config = ServerConfig.builder().host("127.0.0.1").port(0).build();
        try (var child = App.create().get("/events/{id}", new SseHandler() {
            @Override protected void handle(Request request, SseStream stream) throws Exception {
                calls.incrementAndGet();
                stream.send(request.param("id") + ":" + String.join(",", order));
            }
        }, inner).prepare(config); var client = HttpClient.newHttpClient()) {
            Server server = Server.start(App.create().any("/events/**", (req, res) -> res, (exchange, next) -> {
                child.handle(exchange);
                exchange.around(body -> { order.add("outer"); body.call(); });
            }), config);
            try {
                var url = URI.create("http://127.0.0.1:" + server.port() + "/events/42");
                var result = client.send(HttpRequest.newBuilder(url).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, result.statusCode());
                assertEquals("data: 42:outer,inner\n\n", result.body());
            } finally {
                server.stop().join();
            }
            assertEquals(1, calls.get());
            assertEquals(List.of("outer", "inner"), order);
        }
    }

    @Test void startupErrorClosesPreparedServicesAndPreservesTheError() {
        var closed = new AtomicBoolean();
        class Lifetime implements AutoCloseable { public void close() { closed.set(true); } }
        var failure = new AssertionError("startup failed");
        App app = App.create().add(Lifetime.class, new Lifetime()).onStart(services -> { throw failure; });
        var config = ServerConfig.builder().build();
        assertSame(failure, assertThrows(AssertionError.class, () -> app.prepare(config)));
        assertTrue(closed.get());
    }

    public static final class Unbuilt extends RequestHandler {
        public Unbuilt() { throw new AssertionError("Schema inspection must not build handlers"); }
        @Override protected Object handle(Request req, Response res) { return null; }
    }
    @Test void declaredRoutesAreAvailableWithoutStartingServices() {
        App app = App.create().mount("/api", api -> api.route(HttpMethod.GET, "/items/{id}", Unbuilt.class));
        var route = app.routes().getFirst();
        assertEquals("/api/items/{id}", route.path());
        assertEquals(Unbuilt.class, route.handler());
        assertTrue(route.middleware().isEmpty());
    }
    @Test void compilingTwiceDoesNotCloseTheRunningApplication() {
        AtomicBoolean closed = new AtomicBoolean();
        class Lifetime implements AutoCloseable { public void close() { closed.set(true); } }
        App app = App.create().add(Lifetime.class, new Lifetime()).get("/", (req,res) -> "ok");
        var config = ServerConfig.builder().build();
        try (PreparedApp prepared = app.prepare(config)) {
            assertThrows(IllegalStateException.class, () -> app.prepare(config));
            assertFalse(closed.get());
        }
        assertTrue(closed.get());
    }

}
