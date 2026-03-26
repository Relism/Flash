package dev.relism.routing.routers.fastpathrouter;

import dev.relism.http.HttpMethod;
import dev.relism.models.HeaderMap;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.RequestLine;
import dev.relism.models.SimpleHandler;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FastPathRouterImplTest {

    // --- helpers ---

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

    // --- route ---

    @Test
    void route_lazyCompilationAndMatch() throws Exception {
        FastPathRouterImpl router = new FastPathRouterImpl();
        
        router.get("/a", (req, res) -> "A").with();
        router.post("/b", (req, res) -> "B").with();

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
        router.get("/a", (req, res) -> "A").with();

        assertNull(router.route(mockRequest(HttpMethod.GET, "/b")));
        // Wrong method
        assertNull(router.route(mockRequest(HttpMethod.POST, "/a")));
    }

    @Test
    void route_extractsPathParams() throws Exception {
        FastPathRouterImpl router = new FastPathRouterImpl();
        
        router.get("/users/{id}/items/{itemId}", (req, res) -> "Extract").with();

        Request request = mockRequest(HttpMethod.GET, "/users/123/items/456");
        RequestHandler handler = router.route(request);
        
        assertNotNull(handler);
        assertEquals("Extract", handler.handle(request, null));

        // Verify path params were injected
        assertNotNull(request.getPathParams());
        assertEquals("123", request.param("id"));
        assertEquals("456", request.param("itemId"));
    }
}
