package dev.relism.flash;

import dev.relism.flash.http.Exchange;
import dev.relism.flash.routing.ExchangeInterceptor;
import dev.relism.flash.routing.Next;
import dev.relism.flash.routing.Route;
import dev.relism.flash.sse.SseHandler;
import dev.relism.flash.sse.SseStream;
import dev.relism.flash.http.Request;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExchangeInterceptorTest {

    private final HttpClient client = HttpClient.newHttpClient();
    private Server server;

    @AfterEach
    void stop() {
        client.shutdownNow();
        if (server != null) server.stop().join();
    }

    @Test
    void surroundsTheApplicationsErrorBoundaryAndFallback() throws Exception {
        Recording interceptor = new Recording();
        App app = App.create()
                .add(ExchangeInterceptor.class, interceptor)
                .get("/users/{id}", (request, response) -> { throw new IllegalArgumentException("bad"); })
                .onError((failure, request, response) -> response.status(422).body("invalid"));

        server = Server.start(app, ServerConfig.builder().host("127.0.0.1").port(0).build());

        assertEquals(422, get("/users/7").statusCode());
        assertEquals(404, get("/missing").statusCode());
        assertEquals(List.of("GET /users/{id} 422", "fallback 404"), interceptor.seen);
    }

    @Test
    void surroundsTheCompleteDuplexLifecycle() throws Exception {
        List<String> order = new ArrayList<>();
        ExchangeInterceptor interceptor = new ExchangeInterceptor() {
            @Override public Next wrap(Route route, Next exchange) {
                return handled -> {
                    order.add("before");
                    exchange.handle(handled);
                    order.add("after");
                };
            }
        };
        App app = App.create().add(ExchangeInterceptor.class, interceptor)
                .get("/events", new SseHandler() {
                    @Override protected void handle(Request request, SseStream stream) throws Exception {
                        order.add("handler");
                        stream.send("done");
                    }
                });
        server = Server.start(app, ServerConfig.builder().host("127.0.0.1").port(0).build());

        assertEquals("data: done\n\n", get("/events").body());
        assertEquals(List.of("before", "handler", "after"), order);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class Recording implements ExchangeInterceptor {
        final List<String> seen = new ArrayList<>();

        @Override
        public Next wrap(Route route, Next exchange) {
            return handled -> observe(route.method() + " " + route.path(), exchange, handled);
        }

        @Override
        public Next wrapFallback(Next exchange) {
            return handled -> observe("fallback", exchange, handled);
        }

        private void observe(String route, Next next, Exchange exchange) throws Exception {
            next.handle(exchange);
            seen.add(route + " " + exchange.response().getStatusCode());
        }
    }
}
