package dev.relism.routing;

import dev.relism.fpr.core.ByteView;
import dev.relism.http.HttpMethod;
import dev.relism.models.HeaderMap;
import dev.relism.models.Request;
import dev.relism.models.RequestHandler;
import dev.relism.models.RequestLine;
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
                new HeaderMap()
        );
        return new Request(line, new byte[0]);
    }

    // --- mount & route ---

    @Test
    void route_delegatesToSubRouterBasedOnLongestPrefix() {
        GlobalRouter global = new GlobalRouter();

        RequestHandler hApi   = new SimpleHandler((req, res) -> "api");
        RequestHandler hApiV1 = new SimpleHandler((req, res) -> "apiv1");

        global.mount("/api",    new MockSubRouter(hApi));
        global.mount("/api/v1", new MockSubRouter(hApiV1));

        assertEquals(hApiV1, global.route(mockRequest("/api/v1/users")));
        assertEquals(hApi,   global.route(mockRequest("/api/v2/users")));
    }

    @Test
    void route_fallsBackToInternalRouter() throws Exception {
        GlobalRouter global = new GlobalRouter();
        global.doRegister(HttpMethod.GET, "/hello", (req, res) -> "internal", new Middleware[0]);

        RequestHandler resolved = global.route(mockRequest("/hello"));
        assertNotNull(resolved);
        assertEquals("internal", resolved.handle(null, null));
    }

    @Test
    void route_noMatch_returnsNotFoundHandler() {
        GlobalRouter global = new GlobalRouter();
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

        assertEquals(customSubHandler, global.resolveExceptionHandler(mockRequest("/api/fail")));
        assertEquals(global.getExceptionHandler(), global.resolveExceptionHandler(mockRequest("/other")));
    }
}
