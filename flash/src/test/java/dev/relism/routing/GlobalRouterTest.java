package dev.relism.routing;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.HttpMethod;
import dev.relism.models.HeaderMap;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.RequestLine;
import dev.relism.models.Response;
import dev.relism.models.SimpleHandler;
import dev.relism.routing.routers.fastpathrouter.FastPathViews;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class GlobalRouterTest {

    // --- mock ---

    static class MockSubRouter extends AbstractRouter {
        RequestHandler matchedHandler;

        MockSubRouter(RequestHandler handler) {
            this.matchedHandler = handler;
        }

        @Override
        public RequestHandler route(Request request) {
            return matchedHandler;
        }

        @Override
        protected AbstractRouter addRoute(HttpMethod method, String path, RequestHandler handler) {
            return this;
        }
    }

    private Request mockRequest(String path) {
        byte[] bytes = path.getBytes(StandardCharsets.UTF_8);
        FastPathViews.RequestByteView pathView = new FastPathViews.RequestByteView(bytes, 0, bytes.length);

        RequestLine line = new RequestLine(
                HttpMethod.GET, pathView, null,
                new FastPathViews.RequestByteView("HTTP/1.1".getBytes(StandardCharsets.UTF_8), 0, 8),
                new HeaderMap(new byte[0])
        );
        return new Request(line, new byte[0]);
    }

    // --- mount & route ---

    @Test
    void route_delegatesToSubRouterBasedOnLongestPrefix() {
        GlobalRouter global = new GlobalRouter();
        
        RequestHandler hApi = new SimpleHandler((req, res) -> "api");
        RequestHandler hApiV1 = new SimpleHandler((req, res) -> "apiv1");
        
        global.mount("/api", new MockSubRouter(hApi));
        global.mount("/api/v1", new MockSubRouter(hApiV1)); // longer prefix

        // Path matches /api/v1 -> Should pick hApiV1 because it's longer and sorted first
        RequestHandler resolved = global.route(mockRequest("/api/v1/users"));
        assertEquals(hApiV1, resolved);

        // Path matches /api but not /api/v1
        RequestHandler resolved2 = global.route(mockRequest("/api/v2/users"));
        assertEquals(hApi, resolved2);
    }

    @Test
    void route_fallsBackToInternalRouter() throws Exception {
        GlobalRouter global = new GlobalRouter();
        RequestHandler internalHandler = new SimpleHandler((req, res) -> "internal");
        
        global.get("/hello", (req, res) -> "internal");
        // We know it routes to internal. Let's send a request.
        RequestHandler resolved = global.route(mockRequest("/hello"));
        assertNotNull(resolved);
        // It's the compiled FastPathRouter handler, let's verify it works
        assertEquals("internal", resolved.handle(null, null));
    }

    @Test
    void route_noMatch_returnsNotFoundHandler() {
        GlobalRouter global = new GlobalRouter();
        // Nothing registered. Should return the global notFoundHandler.
        RequestHandler resolved = global.route(mockRequest("/unknown"));
        assertEquals(global.getNotFoundHandler(), resolved);
    }

    // --- resolveExceptionHandler ---

    @Test
    void resolveExceptionHandler_returnsScopedHandler() {
        GlobalRouter global = new GlobalRouter();
        MockSubRouter sub = new MockSubRouter(null);
        AbstractRouter.ExceptionHandler customSubHandler = (ex, req, res) -> "sub error";
        sub.onException(customSubHandler);

        global.mount("/api", sub);

        // Under sub-namespace
        assertEquals(customSubHandler, global.resolveExceptionHandler(mockRequest("/api/fail")));
        
        // Outside sub-namespace (global)
        assertEquals(global.getExceptionHandler(), global.resolveExceptionHandler(mockRequest("/other")));
    }
}
