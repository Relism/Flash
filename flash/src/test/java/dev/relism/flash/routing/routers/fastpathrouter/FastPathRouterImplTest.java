package dev.relism.flash.routing.routers.fastpathrouter;

import dev.relism.flash.http.HttpMethod;
import dev.relism.flash.models.HeaderMap;
import dev.relism.flash.models.Request;
import dev.relism.flash.models.RequestHandler;
import dev.relism.flash.models.RequestLine;
import dev.relism.flash.models.SimpleHandler;
import dev.relism.flash.routing.Middleware;
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
        Object scratch = router.newScratch();

        RequestHandler res1 = router.route(mockRequest(HttpMethod.GET, "/a"), scratch);
        assertNotNull(res1);
        assertEquals("A", res1.handle(null, null));

        RequestHandler res2 = router.route(mockRequest(HttpMethod.POST, "/b"), scratch);
        assertNotNull(res2);
        assertEquals("B", res2.handle(null, null));
    }

    @Test
    void route_noMatch_returnsNull() {
        FastPathRouterImpl router = new FastPathRouterImpl();
        router.doRegister(HttpMethod.GET, "/a", new SimpleHandler((req, res) -> "A"), NO_MW);
        Object scratch = router.newScratch();

        assertNull(router.route(mockRequest(HttpMethod.GET, "/b"), scratch));
        assertNull(router.route(mockRequest(HttpMethod.POST, "/a"), scratch));
    }

    @Test
    void route_extractsPathParams() throws Exception {
        FastPathRouterImpl router = new FastPathRouterImpl();
        router.doRegister(HttpMethod.GET, "/users/{id}/items/{itemId}",
                new SimpleHandler((req, res) -> "Extract"), NO_MW);

        Request request = mockRequest(HttpMethod.GET, "/users/123/items/456");
        RequestHandler handler = router.route(request, router.newScratch());

        assertNotNull(handler);
        assertEquals("Extract", handler.handle(request, null));
        assertNotNull(request.getPathParams());
        assertEquals("123", request.param("id"));
        assertEquals("456", request.param("itemId"));
    }

    @Test
    void route_reusesScratchAcrossManyRequests_includingGrowingParamCapacity() throws Exception {
        // EX-19: the same scratch, reused across a mix of param counts, must keep matching
        // correctly as its arrays grow past their initial size (8) and get reused afterward.
        FastPathRouterImpl router = new FastPathRouterImpl();
        router.doRegister(HttpMethod.GET, "/a/{p1}/{p2}/{p3}/{p4}/{p5}/{p6}/{p7}/{p8}/{p9}/{p10}",
                new SimpleHandler((req, res) -> "many"), NO_MW);
        router.doRegister(HttpMethod.GET, "/b/{id}", new SimpleHandler((req, res) -> "one"), NO_MW);
        Object scratch = router.newScratch();

        for (int i = 0; i < 3; i++) {
            Request oneParam = mockRequest(HttpMethod.GET, "/b/123");
            assertEquals("one", router.route(oneParam, scratch).handle(oneParam, null));
            assertEquals("123", oneParam.param("id"));

            Request tenParams = mockRequest(HttpMethod.GET, "/a/1/2/3/4/5/6/7/8/9/10");
            assertEquals("many", router.route(tenParams, scratch).handle(tenParams, null));
            assertEquals("10", tenParams.param("p10"));
            assertEquals("1", tenParams.param("p1"));

            // The 1-param request that follows a 10-param one must not see stale params left
            // over from the larger match in the shared, oversized arrays.
            Request oneParamAgain = mockRequest(HttpMethod.GET, "/b/456");
            RequestHandler h = router.route(oneParamAgain, scratch);
            assertNotNull(h);
            h.handle(oneParamAgain, null);
            assertEquals("456", oneParamAgain.param("id"));
            assertNull(oneParamAgain.param("p10"));
        }
    }
}
