package dev.relism.routing.routers.fastpathrouter;

import dev.relism.http.HttpMethod;
import dev.relism.models.HeaderMap;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.RequestLine;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.Middleware;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FastPathRouterImplTest {

    private static final Middleware[] NO_MW = new Middleware[0];

    private Request mockRequest(HttpMethod method, String path) {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        FastPathViews.RequestByteView pathView = new FastPathViews.RequestByteView(bytes, 0, bytes.length);
        RequestLine line = new RequestLine(
                method, pathView, null,
                new FastPathViews.RequestByteView("HTTP/1.1".getBytes(StandardCharsets.UTF_8), 0, 8),
                new HeaderMap()
        );
        return new Request(line, new byte[0]);
    }

    @Test
    void route_lazyCompilationAndMatch() throws Exception {
        FastPathRouterImpl router = new FastPathRouterImpl();
        router.doRegister(HttpMethod.GET,  "/a", new SimpleHandler((req, res) -> "A"), NO_MW);
        router.doRegister(HttpMethod.POST, "/b", new SimpleHandler((req, res) -> "B"), NO_MW);

        RequestHandler res1 = router.route(mockRequest(HttpMethod.GET, "/a"));
        assertNotNull(res1);
        assertEquals("A", res1.handle(null, null));

        RequestHandler res2 = router.route(mockRequest(HttpMethod.POST, "/b"));
        assertNotNull(res2);
        assertEquals("B", res2.handle(null, null));
    }

    @Test
    void route_noMatch_returnsNull() {
        FastPathRouterImpl router = new FastPathRouterImpl();
        router.doRegister(HttpMethod.GET, "/a", new SimpleHandler((req, res) -> "A"), NO_MW);

        assertNull(router.route(mockRequest(HttpMethod.GET, "/b")));
        assertNull(router.route(mockRequest(HttpMethod.POST, "/a")));
    }

    @Test
    void route_extractsPathParams() throws Exception {
        FastPathRouterImpl router = new FastPathRouterImpl();
        router.doRegister(HttpMethod.GET, "/users/{id}/items/{itemId}",
                new SimpleHandler((req, res) -> "Extract"), NO_MW);

        Request request = mockRequest(HttpMethod.GET, "/users/123/items/456");
        RequestHandler handler = router.route(request);

        assertNotNull(handler);
        assertEquals("Extract", handler.handle(request, null));
        assertNotNull(request.getPathParams());
        assertEquals("123", request.param("id"));
        assertEquals("456", request.param("itemId"));
    }
}
