package dev.relism.flash;

import dev.relism.flash.http.Handler;
import dev.relism.flash.http.Request;
import dev.relism.flash.http.Response;
import dev.relism.flash.routing.ExchangeInterceptor;
import dev.relism.flash.routing.Route;
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

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + path)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static final class Recording implements ExchangeInterceptor {
        final List<String> seen = new ArrayList<>();

        @Override
        public Handler wrap(Route route, Handler exchange) {
            return (request, response) -> observe(route.method() + " " + route.path(), exchange, request, response);
        }

        @Override
        public Handler wrapFallback(Handler exchange) {
            return (request, response) -> observe("fallback", exchange, request, response);
        }

        private Object observe(String route, Handler exchange, Request request, Response response) throws Exception {
            Object answer = exchange.handle(request, response);
            Response actual = answer instanceof Response returned ? returned : response;
            seen.add(route + " " + actual.getStatusCode());
            return answer;
        }
    }
}
